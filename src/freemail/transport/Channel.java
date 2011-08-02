/*
 * Channel.java
 * This file is part of Freemail
 * Copyright (C) 2006,2007,2008 Dave Baker
 * Copyright (C) 2007 Alexander Lehmann
 * Copyright (C) 2009 Martin Nyhus
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package freemail.transport;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.security.SecureRandom;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.archive.util.Base32;
import org.bouncycastle.crypto.AsymmetricBlockCipher;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.RSAEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.crypto.params.RSAKeyParameters;

import freemail.AccountManager;
import freemail.Freemail;
import freemail.FreemailAccount;
import freemail.MessageBank;
import freemail.Postman;
import freemail.SlotManager;
import freemail.SlotSaveCallback;
import freemail.fcp.ConnectionTerminatedException;
import freemail.fcp.FCPBadFileException;
import freemail.fcp.FCPFetchException;
import freemail.fcp.FCPInsertErrorMessage;
import freemail.fcp.HighLevelFCPClient;
import freemail.fcp.SSKKeyPair;
import freemail.utils.DateStringFactory;
import freemail.utils.Logger;
import freemail.utils.PropsFile;
import freemail.wot.Identity;
import freemail.wot.WoTConnection;
import freemail.wot.WoTProperties;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;

//FIXME: The message id gives away how many messages has been sent over the channel.
//       Could it be replaced by a different solution that gives away less information?
class Channel extends Postman {
	private static final String CHANNEL_PROPS_NAME = "props";
	private static final int POLL_AHEAD = 6;

	/**
	 * The amount of time before the channel times out, in milliseconds. If the channel is created
	 * at t=0, then messages won't be queued after t=CHANNEL_TIMEOUT, and the fetcher will stop
	 * when t=2*CHANNEL_TIMEOUT. This should provide enough delay for the recipient to fetch all
	 * the messages.
	 */
	private static final long CHANNEL_TIMEOUT = 7 * 24 * 60 * 60 * 1000; //1 week

	//The keys used in the props file
	private static class PropsKeys {
		private static final String PRIVATE_KEY = "privateKey";
		private static final String PUBLIC_KEY = "publicKey";
		private static final String FETCH_SLOT = "fetchSlot";
		private static final String SEND_SLOT = "sendSlot";
		private static final String MESSAGE_ID = "messageId";
		private static final String SENDER_STATE = "sender-state";
		private static final String RECIPIENT_STATE = "recipient-state";
		private static final String RTS_SENT_AT = "rts-sent-at";
		private static final String SEND_CODE = "sendCode";
		private static final String FETCH_CODE = "fetchCode";
		private static final String REMOTE_ID = "remoteID";
		private static final String TIMEOUT = "timeout";
	}

	private final File channelDir;
	private final PropsFile channelProps;
	private final ScheduledExecutorService executor;
	private final HighLevelFCPClient fcpClient;
	private final Freemail freemail;
	private final FreemailAccount account;
	private final Fetcher fetcher = new Fetcher();
	private final RTSSender rtsSender = new RTSSender();
	private final ChannelEventCallback channelEventCallback;

	Channel(File channelDir, ScheduledExecutorService executor, HighLevelFCPClient fcpClient, Freemail freemail, FreemailAccount account, ChannelEventCallback channelEventCallback) throws ChannelTimedOutException {
		if(executor == null) throw new NullPointerException();
		this.executor = executor;

		this.fcpClient = fcpClient;
		this.account = account;
		this.channelEventCallback = channelEventCallback;

		if(freemail == null) throw new NullPointerException();
		this.freemail = freemail;

		assert channelDir.isDirectory();
		this.channelDir = channelDir;

		File channelPropsFile = new File(channelDir, CHANNEL_PROPS_NAME);
		if(!channelPropsFile.exists()) {
			try {
				if(!channelPropsFile.createNewFile()) {
					Logger.error(this, "Could not create new props file in " + channelDir);
				}
			} catch(IOException e) {
				Logger.error(this, "Could not create new props file in " + channelDir);
			}
		}
		channelProps = PropsFile.createPropsFile(channelPropsFile);

		//Check if the channel has timed out
		synchronized(channelProps) {
			String rawTimeout = channelProps.get(PropsKeys.TIMEOUT);
			if(rawTimeout != null) {
				try {
					long timeout = Long.parseLong(rawTimeout);
					if(timeout < (System.currentTimeMillis() - CHANNEL_TIMEOUT)) {
						Logger.debug(this, "Channel has timed out");
						throw new ChannelTimedOutException();
					}
				} catch(NumberFormatException e) {
					Logger.error(this, "Illegal value in " + PropsKeys.TIMEOUT + " field, assuming timed out: " + rawTimeout);
					throw new ChannelTimedOutException();
				}
			}
		}

		//Message id is needed for queuing messages so it must be present
		if(channelProps.get(PropsKeys.MESSAGE_ID) == null) {
			channelProps.put(PropsKeys.MESSAGE_ID, "0");
		}
	}

	void processRTS(PropsFile rtsProps) {
		Logger.debug(this, "Processing RTS");

		synchronized(channelProps) {
			//Because of the way InsertableClientSSK works we need to add a document name (the part
			//after the final /) to the key before it is passed to FreenetURI. This must be removed
			//again when we store the keys to the props file
			String privateKey = "";
			String publicKey = "";
			try {
				final String documentName = "documentName";

				FreenetURI privateURI = new FreenetURI(rtsProps.get("channel") + documentName);
				InsertableClientSSK insertableKey = InsertableClientSSK.create(privateURI);

				privateKey = insertableKey.getInsertURI().toString();
				privateKey = privateKey.substring(0, privateKey.length() - documentName.length());

				publicKey = insertableKey.getURI().toString();
				publicKey = publicKey.substring(0, publicKey.length() - documentName.length());
			} catch(MalformedURLException e) {
				Logger.debug(this, "RTS contained malformed private key: " + rtsProps.get("channel"));
				return;
			}

			if(!privateKey.equals(channelProps.get(PropsKeys.PRIVATE_KEY))) {
				/* The keys in the RTS are not the same as the ones we already have (if we have
				 * any). This will happen when the other side deletes the channel and then sends us
				 * another message. Delete the old values so we start using the new ones, except
				 * message id since we might still have messages in the outbox.
				 */
				channelProps.remove(PropsKeys.PRIVATE_KEY);
				channelProps.remove(PropsKeys.PUBLIC_KEY);
				channelProps.remove(PropsKeys.SEND_CODE);
				channelProps.remove(PropsKeys.SEND_SLOT);
				channelProps.remove(PropsKeys.FETCH_CODE);
				channelProps.remove(PropsKeys.FETCH_SLOT);
				channelProps.remove(PropsKeys.RECIPIENT_STATE);
			}

			if(channelProps.get(PropsKeys.RECIPIENT_STATE) != null) {
				Logger.debug(this, "Skipping RTS processing because recipient state isn't null");
				return;
			}

			if(channelProps.get(PropsKeys.PRIVATE_KEY) == null) {
				channelProps.put(PropsKeys.PRIVATE_KEY, privateKey);
				channelProps.put(PropsKeys.PUBLIC_KEY, publicKey);
			}

			channelProps.put(PropsKeys.FETCH_SLOT, rtsProps.get("initiatorSlot"));
			channelProps.put(PropsKeys.FETCH_CODE, "i");

			if(channelProps.get(PropsKeys.SEND_CODE) == null) {
				channelProps.put(PropsKeys.SEND_CODE, "r");
			}

			if(channelProps.get(PropsKeys.SEND_SLOT) == null) {
				channelProps.put(PropsKeys.SEND_SLOT, rtsProps.get("responderSlot"));
			}

			if(channelProps.get(PropsKeys.MESSAGE_ID) == null) {
				channelProps.put(PropsKeys.MESSAGE_ID, "0");
			}

			channelProps.put(PropsKeys.TIMEOUT, rtsProps.get("timeout"));
			channelProps.put(PropsKeys.RECIPIENT_STATE, "rts-received");
		}

		//Queue the CTS insert
		queueCTS();
		startTasks();
	}

	void setRemoteIdentity(String remoteID) {
		synchronized(channelProps) {
			channelProps.put(PropsKeys.REMOTE_ID, remoteID);
		}
	}

	public static boolean deleteChannel(File channelDir) {
		File channelPropsFile = new File(channelDir, CHANNEL_PROPS_NAME);
		channelPropsFile.delete();

		return channelDir.delete();
	}

	private void queueCTS() {
		long messageId;
		synchronized(channelProps) {
			messageId = Long.parseLong(channelProps.get(PropsKeys.MESSAGE_ID));
			channelProps.put(PropsKeys.MESSAGE_ID, messageId + 1);
		}

		//Build the header of the inserted message
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintWriter pw = new PrintWriter(baos);
		pw.print("messagetype=cts\r\n");
		pw.print("\r\n");
		pw.close();
		InputStream message = new ByteArrayInputStream(baos.toByteArray());

		insertMessage(message);
	}

	void startTasks() {
		startFetcher();
		startRTSSender();
	}

	private void startFetcher() {
		//Start fetcher if possible
		String fetchSlot;
		String fetchCode;
		String publicKey;
		synchronized(channelProps) {
			fetchSlot = channelProps.get(PropsKeys.FETCH_SLOT);
			fetchCode = channelProps.get(PropsKeys.FETCH_CODE);
			publicKey = channelProps.get(PropsKeys.PUBLIC_KEY);
		}

		if((fetchSlot != null) && (fetchCode != null) && (publicKey != null)) {
			fetcher.execute();
		}
	}

	private void startRTSSender() {
		String state;
		synchronized(channelProps) {
			state = channelProps.get(PropsKeys.SENDER_STATE);
		}

		if((state != null) && state.equals("cts-received")) {
			return;
		}

		rtsSender.execute();
	}

	/**
	 * Places the data read from {@code message} on the send queue
	 * @param message the data to be sent
	 * @return {@code true} if the message was placed on the queue
	 * @throws ChannelTimedOutException if the channel has timed out and can't be used for sending
	 *             messages
	 * @throws NullPointerException if {@code message} is {@code null}
	 */
	boolean sendMessage(Bucket message) throws ChannelTimedOutException {
		if(message == null) throw new NullPointerException("Parameter message was null");

		synchronized(channelProps) {
			String rawTimeout = channelProps.get(PropsKeys.TIMEOUT);
			if(rawTimeout != null) {
				long timeout;
				try {
					timeout = Long.parseLong(rawTimeout);
				} catch(NumberFormatException e) {
					timeout = 0;
				}

				if(timeout < System.currentTimeMillis()) {
					throw new ChannelTimedOutException();
				}
			}
		}

		long messageId;
		synchronized(channelProps) {
			messageId = Long.parseLong(channelProps.get(PropsKeys.MESSAGE_ID));
			channelProps.put(PropsKeys.MESSAGE_ID, messageId + 1);
		}

		//Build the header of the inserted message
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintWriter pw = new PrintWriter(baos);
		pw.print("messagetype=message\r\n");
		pw.print("id=" + messageId + "\r\n");
		pw.print("\r\n");
		pw.close();
		byte[] headerBytes = baos.toByteArray();

		InputStream messageStream = null;
		try {
			messageStream = message.getInputStream();
			return insertMessage(new SequenceInputStream(new ByteArrayInputStream(headerBytes), messageStream));
		} catch(IOException e) {
			Logger.error(this, "Caugth " + e);
			return false;
		} finally {
			Closer.close(messageStream);
		}
	}

	/**
	 * Inserts the given message to the next available slot, returning {@code true} if the message
	 * was inserted, {@code false} otherwise.
	 * @param message the message that should be inserted
	 * @return {@code true} if the message was inserted, {@code false} otherwise
	 */
	private boolean insertMessage(InputStream message) {
		String baseKey;
		synchronized(channelProps) {
			baseKey = channelProps.get(PropsKeys.PRIVATE_KEY);
		}
		if(baseKey == null) {
			Logger.debug(this, "Can't insert, missing private key");
			return false;
		}

		String sendCode;
		synchronized(channelProps) {
			sendCode = channelProps.get(PropsKeys.SEND_CODE);
		}
		if(sendCode == null) {
			Logger.error(this, "Contact " + channelDir.getName() + " is corrupt - account file has no '" + PropsKeys.SEND_CODE + "' entry!");
			//TODO: Either delete the channel or resend the RTS
			return false;
		}
		baseKey += sendCode + "-";

		try {
			while(true) {
				//FIXME: This locking must be broken up, since it blocks *everything*
				synchronized(channelProps) {
					String slot = channelProps.get(PropsKeys.SEND_SLOT);

					Logger.debug(this, "Inserting data to " + baseKey + slot);
					FCPInsertErrorMessage fcpMessage = fcpClient.put(message, baseKey + slot);

					if(fcpMessage == null) {
						Logger.debug(this, "Insert successful");
						slot = calculateNextSlot(slot);
						synchronized(channelProps) {
							channelProps.put(PropsKeys.SEND_SLOT, slot);
						}

						return true;
					}

					if(fcpMessage.errorcode == FCPInsertErrorMessage.COLLISION) {
						slot = calculateNextSlot(slot);

						//Write the new slot each round so we won't have
						//to check all of them again if we fail
						channelProps.put(PropsKeys.SEND_SLOT, slot);

						Logger.debug(this, "Insert collided, trying slot " + slot);
						continue;
					}

					Logger.debug(this, "Insert failed, error code " + fcpMessage.errorcode);
					return false;
				}
			}
		} catch(FCPBadFileException e) {
			Logger.debug(this, "Caugth " + e);
			return false;
		} catch(ConnectionTerminatedException e) {
			Logger.debug(this, "Caugth " + e);
			return false;
		}
	}

	@Override
	public String toString() {
		return "Channel [" + channelDir + "]";
	}

	String getRemoteIdentity() {
		synchronized(channelProps) {
			return channelProps.get(PropsKeys.REMOTE_ID);
		}
	}

	String getPrivateKey() {
		synchronized(channelProps) {
			return channelProps.get(PropsKeys.PRIVATE_KEY);
		}
	}

	boolean canSendMessages() {
		synchronized(channelProps) {
			String rawTimeout = channelProps.get(PropsKeys.TIMEOUT);
			long timeout;
			try {
				timeout = Long.parseLong(rawTimeout);
			} catch(NumberFormatException e) {
				return false;
			}

			return timeout >= System.currentTimeMillis();
		}
	}

	private String calculateNextSlot(String slot) {
		byte[] buf = Base32.decode(slot);
		SHA256Digest sha256 = new SHA256Digest();
		sha256.update(buf, 0, buf.length);
		sha256.doFinal(buf, 0);

		return Base32.encode(buf);
	}

	private String generateRandomSlot() {
		SHA256Digest sha256 = new SHA256Digest();
		byte[] buf = new byte[sha256.getDigestSize()];
		SecureRandom rnd = new SecureRandom();
		rnd.nextBytes(buf);
		return Base32.encode(buf);
	}

	private class Fetcher implements Runnable {
		@Override
		public synchronized void run() {
			Logger.debug(this, "Fetcher running (" + this + ")");

			try {
				realRun();
			} catch(RuntimeException e) {
				Logger.debug(this, "Caugth " + e);
				e.printStackTrace();
				throw e;
			} catch(Error e) {
				Logger.debug(this, "Caugth " + e);
				e.printStackTrace();
				throw e;
			}
		}

		private void realRun() {
			synchronized(channelProps) {
				String rawTimeout = channelProps.get(PropsKeys.TIMEOUT);
				long timeout;
				try {
					timeout = Long.parseLong(rawTimeout);
				} catch(NumberFormatException e) {
					//Assume we haven't timed out
					timeout = Long.MAX_VALUE;
				}

				//Check if we've timed out. The extra time is added because we want to stop fetching
				//later than we stop sending. See JavaDoc for CHANNEL_TIMEOUT for details
				if(timeout < (System.currentTimeMillis() - CHANNEL_TIMEOUT)) {
					Logger.debug(this, "Channel has timed out, won't fetch");
					return;
				}
			}

			String slots;
			synchronized(channelProps) {
				slots = channelProps.get(PropsKeys.FETCH_SLOT);
			}
			if(slots == null) {
				Logger.error(this, "Channel " + channelDir.getName() + " is corrupt - account file has no '" + PropsKeys.FETCH_SLOT + "' entry!");
				//TODO: Either delete the channel or resend the RTS
				return;
			}

			HashSlotManager slotManager = new HashSlotManager(new ChannelSlotSaveImpl(channelProps, PropsKeys.FETCH_SLOT), null, slots);
			slotManager.setPollAhead(POLL_AHEAD);

			String basekey;
			synchronized(channelProps) {
				basekey = channelProps.get(PropsKeys.PUBLIC_KEY);
			}
			if(basekey == null) {
				Logger.error(this, "Contact " + channelDir.getName() + " is corrupt - account file has no '" + PropsKeys.PUBLIC_KEY + "' entry!");
				//TODO: Either delete the channel or resend the RTS
				return;
			}

			String fetchCode;
			synchronized(channelProps) {
				fetchCode = channelProps.get(PropsKeys.FETCH_CODE);
			}

			if(fetchCode == null) {
				Logger.error(this, "Contact " + channelDir.getName() + " is corrupt - account file has no '" + PropsKeys.FETCH_CODE + "' entry!");
				//TODO: Either delete the channel or resend the RTS
				return;
			}
			basekey += fetchCode + "-";

			String slot;
			while((slot = slotManager.getNextSlot()) != null) {
				String key = basekey + slot;

				Logger.minor(this, "Attempting to fetch mail on key " + key);
				File result;
				try {
					result = fcpClient.fetch(key);
				} catch(ConnectionTerminatedException e) {
					Logger.debug(this, "Connection terminated");
					return;
				} catch(FCPFetchException e) {
					if(e.getCode() == FCPFetchException.INVALID_URI) {
						//Could be a local bug or we could have gotten a bad key in the RTS
						//TODO: This won't fix itself, so make sure the user notices
						Logger.error(this, "Fetch failed because the URI was invalid");
						return;
					}

					if(e.isFatal()) {
						Logger.normal(this, "Fatal fetch failure, marking slot as used");
						slotManager.slotUsed();
					}

					Logger.minor(this, "No mail in slot (fetch returned " + e.getMessage() + ")");
					continue;
				}
				Logger.debug(this, "Fetch successful");

				PropsFile messageProps = PropsFile.createPropsFile(result, true);
				String messageType = messageProps.get("messagetype");

				if(messageType == null) {
					Logger.error(this, "Got message without messagetype, discarding");
					slotManager.slotUsed();
					result.delete();
					continue;
				}

				if(messageType.equals("message")) {
					try {
						if(handleMessage(result, account.getMessageBank())) {
							slotManager.slotUsed();
						}
					} catch(ConnectionTerminatedException e) {
						Logger.debug(this, "Connection terminated");
						result.delete();
						return;
					}
				} else if(messageType.equals("cts")) {
					Logger.minor(this, "Successfully received CTS");

					boolean success;
					synchronized(channelProps) {
						success = channelProps.put("status", "cts-received");
					}

					if(success) {
						slotManager.slotUsed();
					}
				} else if(messageType.equals("ack")) {
					if(handleAck(result)) {
						slotManager.slotUsed();
					}
				} else {
					Logger.error(this, "Got message of unknown type: " + messageType);
					slotManager.slotUsed();
				}

				if(!result.delete()) {
					Logger.error(this, "Deletion of " + result + " failed");
				}
			}

			//Reschedule
			schedule(5, TimeUnit.MINUTES);
		}

		public void execute() {
			Logger.debug(this, "Scheduling Fetcher for execution");
			executor.execute(fetcher);
		}

		public void schedule(long delay, TimeUnit unit) {
			Logger.debug(this, "Scheduling Fetcher for execution in " + delay + " " + unit.toString().toLowerCase());
			executor.schedule(fetcher, delay, unit);
		}

		@Override
		public String toString() {
			return "Fetcher [" + channelDir + "]";
		}
	}

	private class RTSSender implements Runnable {
		@Override
		public synchronized void run() {
			Logger.debug(this, "RTSSender running (" + this + ")");

			try {
				realRun();
			} catch(RuntimeException e) {
				Logger.debug(this, "Caugth " + e);
				e.printStackTrace();
				throw e;
			} catch(Error e) {
				Logger.debug(this, "Caugth " + e);
				e.printStackTrace();
				throw e;
			}
		}

		private void realRun() {
			//Check when the RTS should be sent
			long sendRtsIn = sendRTSIn();
			if(sendRtsIn < 0) {
				return;
			}
			if(sendRtsIn > 0) {
				Logger.debug(this, "Rescheduling RTSSender in " + sendRtsIn + " ms when the RTS is due to be inserted");
				schedule(sendRtsIn, TimeUnit.MILLISECONDS);
				return;
			}

			//Get mailsite key from WoT
			WoTConnection wotConnection = freemail.getWotConnection();
			if(wotConnection == null) {
				//WoT isn't loaded, so try again later
				Logger.debug(this, "WoT not loaded, trying again in 5 minutes");
				schedule(5, TimeUnit.MINUTES);
				return;
			}

			String remoteId;
			synchronized(channelProps) {
				 remoteId = channelProps.get(PropsKeys.REMOTE_ID);
			}
			if(remoteId == null) {
				Logger.debug(this, "Missing remote identity");
				return;
			}

			String senderId = account.getUsername();
			Logger.debug(this, "Getting identity from WoT");
			Identity recipient = wotConnection.getIdentity(remoteId, senderId);
			if(recipient == null) {
				Logger.debug(this, "Didn't get identity from WoT, trying again in 5 minutes");
				schedule(5, TimeUnit.MINUTES);
				return;
			}

			//Get the mailsite edition
			int mailisteEdition;
			String edition = wotConnection.getProperty(remoteId, WoTProperties.MAILSITE_EDITION);
			if(edition == null) {
				mailisteEdition = 1;
			} else {
				try {
					mailisteEdition = Integer.parseInt(edition);
				} catch(NumberFormatException e) {
					mailisteEdition = 1;
				}
			}

			//Strip the WoT part from the key and add the Freemail path
			String mailsiteKey = recipient.getRequestURI();
			mailsiteKey = mailsiteKey.substring(0, mailsiteKey.indexOf("/"));
			mailsiteKey = mailsiteKey + "/mailsite/-" + mailisteEdition + "/mailpage";

			//Fetch the mailsite
			File mailsite;
			try {
				Logger.debug(this, "Fetching mailsite from " + mailsiteKey);
				mailsite = fcpClient.fetch(mailsiteKey);
			} catch(ConnectionTerminatedException e) {
				Logger.debug(this, "FCP connection has been terminated");
				return;
			} catch(FCPFetchException e) {
				Logger.debug(this, "Mailsite fetch failed (" + e + "), trying again in 5 minutes");
				schedule(5, TimeUnit.MINUTES);
				return;
			}

			//Get RTS KSK
			PropsFile mailsiteProps = PropsFile.createPropsFile(mailsite, false);
			String rtsKey = mailsiteProps.get("rtsksk");

			//Get or generate RTS values
			String privateKey;
			String publicKey;
			String initiatorSlot;
			String responderSlot;
			long timeout;
			synchronized(channelProps) {
				privateKey = channelProps.get(PropsKeys.PRIVATE_KEY);
				publicKey = channelProps.get(PropsKeys.PUBLIC_KEY);
				initiatorSlot = channelProps.get(PropsKeys.SEND_SLOT);
				responderSlot = channelProps.get(PropsKeys.FETCH_SLOT);

				if((privateKey == null) || (publicKey == null)) {
					SSKKeyPair keyPair;
					try {
						Logger.debug(this, "Making new key pair");
						keyPair = fcpClient.makeSSK();
					} catch(ConnectionTerminatedException e) {
						Logger.debug(this, "FCP connection has been terminated");
						return;
					}
					privateKey = keyPair.privkey;
					publicKey = keyPair.pubkey;
				}
				if(initiatorSlot == null) {
					initiatorSlot = generateRandomSlot();
				}
				if(responderSlot == null) {
					responderSlot = generateRandomSlot();
				}

				timeout = System.currentTimeMillis() + CHANNEL_TIMEOUT;

				channelProps.put(PropsKeys.PUBLIC_KEY, publicKey);
				channelProps.put(PropsKeys.PRIVATE_KEY, privateKey);
				channelProps.put(PropsKeys.SEND_SLOT, initiatorSlot);
				channelProps.put(PropsKeys.FETCH_SLOT, responderSlot);
				channelProps.put(PropsKeys.SEND_CODE, "i");
				channelProps.put(PropsKeys.FETCH_CODE, "r");
				channelProps.put(PropsKeys.TIMEOUT, "" + timeout);
			}

			//Get the senders mailsite key
			Logger.debug(this, "Getting sender identity from WoT");
			Identity senderIdentity = wotConnection.getIdentity(senderId, senderId);
			if(senderIdentity == null) {
				Logger.debug(this, "Didn't get identity from WoT, trying again in 5 minutes");
				schedule(5, TimeUnit.MINUTES);
				return;
			}

			int senderMailsiteEdition;
			String senderEdition = wotConnection.getProperty(account.getUsername(), WoTProperties.MAILSITE_EDITION);
			if(edition == null) {
				senderMailsiteEdition = 1;
			} else {
				try {
					senderMailsiteEdition = Integer.parseInt(senderEdition);
				} catch(NumberFormatException e) {
					senderMailsiteEdition = 1;
				}
			}

			String senderMailsiteKey = senderIdentity.getRequestURI();
			senderMailsiteKey = senderMailsiteKey.substring(0, senderMailsiteKey.indexOf("/"));
			senderMailsiteKey = senderMailsiteKey + "/mailsite/-" + senderMailsiteEdition + "/mailpage";

			//Now build the RTS
			byte[] rtsMessageBytes = buildRTSMessage(senderMailsiteKey, recipient.getIdentityID(), privateKey, initiatorSlot, responderSlot, timeout);
			if(rtsMessageBytes == null) {
				return;
			}

			//Sign the message
			byte[] signedMessage = signRtsMessage(rtsMessageBytes);
			if(signedMessage == null) {
				return;
			}

			//Encrypt the message using the recipients public key
			String keyModulus = mailsiteProps.get("asymkey.modulus");
			if(keyModulus == null) {
				Logger.error(this, "Mailsite is missing public key modulus");
				schedule(1, TimeUnit.HOURS);
				return;
			}

			String keyExponent = mailsiteProps.get("asymkey.pubexponent");
			if(keyExponent == null) {
				Logger.error(this, "Mailsite is missing public key exponent");
				schedule(1, TimeUnit.HOURS);
				return;
			}

			byte[] rtsMessage = encryptMessage(signedMessage, keyModulus, keyExponent);

			//Insert
			int slot;
			try {
				String key = "KSK@" + rtsKey + "-" + DateStringFactory.getKeyString();
				Logger.debug(this, "Inserting RTS to " + key);
				slot = fcpClient.slotInsert(rtsMessage, key, 1, "");
			} catch(ConnectionTerminatedException e) {
				return;
			}
			if(slot < 0) {
				Logger.debug(this, "Slot insert failed, trying again in 5 minutes");
				schedule(5, TimeUnit.MINUTES);
				return;
			}

			//Update channel props file
			synchronized(channelProps) {
				//Check if we've gotten the CTS while inserting the RTS
				if(!"cts-received".equals(channelProps.get(PropsKeys.SENDER_STATE))) {
					channelProps.put(PropsKeys.SENDER_STATE, "rts-sent");
				}
				channelProps.put(PropsKeys.RTS_SENT_AT, Long.toString(System.currentTimeMillis()));
			}

			long delay = sendRTSIn();
			if(delay < 0) {
				return;
			}
			Logger.debug(this, "Rescheduling RTSSender to run in " + delay + " ms when the reinsert is due");
			schedule(delay, TimeUnit.MILLISECONDS);

			//Start the fetcher now that we have keys, slots etc.
			fetcher.execute();
		}

		public void execute() {
			Logger.debug(this, "Scheduling RTSSender for execution");
			executor.execute(this);
		}

		public void schedule(long delay, TimeUnit unit) {
			Logger.debug(this, "Scheduling RTSSender for execution in " + delay + " " + unit.toString().toLowerCase());
			executor.schedule(this, delay, unit);
		}

		/**
		 * Returns the number of milliseconds left to when the RTS should be sent, or -1 if it
		 * should not be sent.
		 * @return the number of milliseconds left to when the RTS should be sent
		 */
		private long sendRTSIn() {
			//Check if the CTS has been received
			String senderState;
			String recipientState;
			synchronized(channelProps) {
				senderState = channelProps.get(PropsKeys.SENDER_STATE);
				recipientState = channelProps.get(PropsKeys.RECIPIENT_STATE);
			}
			if((senderState != null) && senderState.equals("cts-received")) {
				Logger.debug(this, "CTS has been received");
				return -1;
			}
			if((recipientState != null) && (recipientState.equals("rts-received") || recipientState.equals("cts-sent"))) {
				//We've received an RTS from the other side
				Logger.debug(this, "RTS received from other side");
				return -1;
			}

			//Check when the RTS should be (re)sent
			String rtsSentAt;
			synchronized(channelProps) {
				rtsSentAt = channelProps.get(PropsKeys.RTS_SENT_AT);
			}

			if(rtsSentAt != null) {
				long sendTime;
				try {
					sendTime = Long.parseLong(rtsSentAt);
				} catch(NumberFormatException e) {
					Logger.debug(this, "Illegal value in " + PropsKeys.RTS_SENT_AT + " field, assuming 0");
					sendTime = 0;
				}

				long timeToResend = (24 * 60 * 60 * 1000) - (System.currentTimeMillis() - sendTime);
				if(timeToResend > 0) {
					return timeToResend;
				}
			}

			//Send the RTS immediately
			return 0;
		}

		private byte[] buildRTSMessage(String senderMailsiteKey, String recipientIdentityID, String channelPrivateKey, String initiatorSlot, String responderSlot, long timeout) {
			assert (senderMailsiteKey.matches("^USK@\\S{43,44},\\S{43,44},\\S{7}/\\w+/-?[0-9]+/.*$")) : "Malformed sender mailsite: " + senderMailsiteKey;
			assert (recipientIdentityID != null);
			assert (channelPrivateKey.matches("^SSK@\\S{43,44},\\S{43,44},\\S{7}/$")) : "Malformed channel key: " + channelPrivateKey;
			assert (initiatorSlot != null);
			assert (responderSlot != null);
			assert (timeout > System.currentTimeMillis());

			StringBuffer rtsMessage = new StringBuffer();
			rtsMessage.append("mailsite=" + senderMailsiteKey + "\r\n");
			rtsMessage.append("to=" + recipientIdentityID + "\r\n");
			rtsMessage.append("channel=" + channelPrivateKey + "\r\n");
			rtsMessage.append("initiatorSlot=" + initiatorSlot + "\r\n");
			rtsMessage.append("responderSlot=" + responderSlot + "\r\n");
			rtsMessage.append("timeout=" + timeout + "\r\n");
			rtsMessage.append("\r\n");

			byte[] rtsMessageBytes;
			try {
				rtsMessageBytes = rtsMessage.toString().getBytes("UTF-8");
			} catch(UnsupportedEncodingException e) {
				Logger.error(this, "JVM doesn't support UTF-8 charset");
				e.printStackTrace();
				return null;
			}

			return rtsMessageBytes;
		}

		private byte[] signRtsMessage(byte[] rtsMessageBytes) {
			SHA256Digest sha256 = new SHA256Digest();
			sha256.update(rtsMessageBytes, 0, rtsMessageBytes.length);
			byte[] hash = new byte[sha256.getDigestSize()];
			sha256.doFinal(hash, 0);

			RSAKeyParameters ourPrivateKey = AccountManager.getPrivateKey(account.getProps());

			AsymmetricBlockCipher signatureCipher = new RSAEngine();
			signatureCipher.init(true, ourPrivateKey);
			byte[] signature = null;
			try {
				signature = signatureCipher.processBlock(hash, 0, hash.length);
			} catch(InvalidCipherTextException e) {
				Logger.error(this, "Failed to RSA encrypt hash: " + e.getMessage());
				e.printStackTrace();
				return null;
			}

			byte[] signedMessage = new byte[rtsMessageBytes.length + signature.length];
			System.arraycopy(rtsMessageBytes, 0, signedMessage, 0, rtsMessageBytes.length);
			System.arraycopy(signature, 0, signedMessage, rtsMessageBytes.length, signature.length);

			return signedMessage;
		}

		private byte[] encryptMessage(byte[] signedMessage, String keyModulus, String keyExponent) {
			//Make a new symmetric key for the message
			byte[] aesKeyAndIV = new byte[32 + 16];
			SecureRandom rnd = new SecureRandom();
			rnd.nextBytes(aesKeyAndIV);

			//Encrypt the message with the new symmetric key
			PaddedBufferedBlockCipher aesCipher = new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESEngine()), new PKCS7Padding());
			KeyParameter aesKeyParameters = new KeyParameter(aesKeyAndIV, 16, 32);
			ParametersWithIV aesParameters = new ParametersWithIV(aesKeyParameters, aesKeyAndIV, 0, 16);
			aesCipher.init(true, aesParameters);

			byte[] encryptedMessage = new byte[aesCipher.getOutputSize(signedMessage.length)];
			int offset = aesCipher.processBytes(signedMessage, 0, signedMessage.length, encryptedMessage, 0);

			try {
				aesCipher.doFinal(encryptedMessage, offset);
			} catch(InvalidCipherTextException e) {
				Logger.error(this, "Failed to perform symmertic encryption on RTS data: " + e.getMessage());
				e.printStackTrace();
				return null;
			}

			RSAKeyParameters recipientPublicKey = new RSAKeyParameters(false, new BigInteger(keyModulus, 32), new BigInteger(keyExponent, 32));
			AsymmetricBlockCipher keyCipher = new RSAEngine();
			keyCipher.init(true, recipientPublicKey);
			byte[] encryptedAesParameters = null;
			try {
				encryptedAesParameters = keyCipher.processBlock(aesKeyAndIV, 0, aesKeyAndIV.length);
			} catch(InvalidCipherTextException e) {
				Logger.error(this, "Failed to perform asymmertic encryption on RTS symmetric key: " + e.getMessage());
				e.printStackTrace();
				return null;
			}

			//Assemble the final message
			byte[] rtsMessage = new byte[encryptedAesParameters.length + encryptedMessage.length];
			System.arraycopy(encryptedAesParameters, 0, rtsMessage, 0, encryptedAesParameters.length);
			System.arraycopy(encryptedMessage, 0, rtsMessage, encryptedAesParameters.length, encryptedMessage.length);

			return rtsMessage;
		}

		@Override
		public String toString() {
			return "RTSSender [" + channelDir + "]";
		}
	}

	private boolean handleMessage(File msg, MessageBank mb) throws ConnectionTerminatedException {
		// parse the Freemail header(s) out.
		PropsFile msgprops = PropsFile.createPropsFile(msg, true);
		String s_id = msgprops.get("id");
		if (s_id == null) {
			Logger.error(this,"Message is missing id. Discarding.");
			msgprops.closeReader();
			return true;
		}

		int id;
		try {
			id = Integer.parseInt(s_id);
		} catch (NumberFormatException nfe) {
			Logger.error(this,"Got a message with an invalid (non-integer) id. Discarding.");
			msgprops.closeReader();
			return true;
		}

		MessageLog msglog = new MessageLog(this.channelDir);
		boolean isDupe;
		try {
			isDupe = msglog.isPresent(id);
		} catch (IOException ioe) {
			Logger.error(this,"Couldn't read logfile, so don't know whether received message is a duplicate or not. Leaving in the queue to try later.");
			msgprops.closeReader();
			return false;
		}
		if (isDupe) {
			Logger.normal(this,"Got a message, but we've already logged that message ID as received. Discarding.");
			msgprops.closeReader();
			return true;
		}

		BufferedReader br = msgprops.getReader();
		if (br == null) {
			Logger.error(this,"Got an invalid message. Discarding.");
			msgprops.closeReader();
			return true;
		}

		try {
			this.storeMessage(br, mb);
		} catch (IOException ioe) {
			return false;
		}
		Logger.normal(this,"You've got mail!");
		try {
			msglog.add(id);
		} catch (IOException ioe) {
			// how should we handle this? Remove the message from the inbox again?
			Logger.error(this,"warning: failed to write log file!");
		}

		queueAck(id);

		return true;
	}

	private void queueAck(int ackId) {
		long messageId;
		synchronized(channelProps) {
			messageId = Long.parseLong(channelProps.get(PropsKeys.MESSAGE_ID));
			channelProps.put(PropsKeys.MESSAGE_ID, messageId + 1);
		}

		//Build the header of the inserted message
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintWriter pw = new PrintWriter(baos);
		pw.print("messagetype=ack\r\n");
		pw.print("id=" + ackId + "\r\n");
		pw.print("\r\n");
		pw.close();
		InputStream message = new ByteArrayInputStream(baos.toByteArray());

		insertMessage(message);
	}

	private boolean handleAck(File result) {
		PropsFile ackProps = PropsFile.createPropsFile(result);
		String id = ackProps.get("id");
		if(id == null) {
			Logger.debug(this, "Received ack without id, discarding");
			return true;
		}

		Logger.debug(this, "Got ack with id " + id);

		long messageId = Long.parseLong(id);
		channelEventCallback.onAckReceived(messageId);

		return true;
	}

	private class HashSlotManager extends SlotManager {
		HashSlotManager(SlotSaveCallback cb, Object userdata, String slotlist) {
			super(cb, userdata, slotlist);
		}

		@Override
		protected String incSlot(String slot) {
			return calculateNextSlot(slot);
		}
	}

	private static class MessageLog {
		private static final String LOGFILE = "log";
		private final File logfile;

		public MessageLog(File ibctdir) {
			this.logfile = new File(ibctdir, LOGFILE);
		}

		public boolean isPresent(int targetid) throws IOException {
			BufferedReader br;
			try {
				br = new BufferedReader(new FileReader(this.logfile));
			} catch (FileNotFoundException fnfe) {
				return false;
			}

			String line;
			while ( (line = br.readLine()) != null) {
				int curid = Integer.parseInt(line);
				if (curid == targetid) {
					br.close();
					return true;
				}
			}

			br.close();
			return false;
		}

		public void add(int id) throws IOException {
			FileOutputStream fos = new FileOutputStream(this.logfile, true);

			PrintStream ps = new PrintStream(fos);
			ps.println(id);
			ps.close();
		}
	}

	private static class ChannelSlotSaveImpl implements SlotSaveCallback {
		private final PropsFile propsFile;
		private final String keyName;

		private ChannelSlotSaveImpl(PropsFile propsFile, String keyName) {
			this.propsFile = propsFile;
			this.keyName = keyName;
		}

		@Override
		public void saveSlots(String slots, Object userdata) {
			synchronized(propsFile) {
				propsFile.put(keyName, slots);
			}
		}
	}

	public interface ChannelEventCallback {
		public void onAckReceived(long id);
	}
}
