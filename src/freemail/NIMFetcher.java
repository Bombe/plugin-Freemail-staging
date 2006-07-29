package freemail;

import freemail.fcp.HighLevelFCPClient;
import freemail.utils.DateStringFactory;

import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Date;
import java.util.Calendar;
import java.util.TimeZone;

public class NIMFetcher extends Postman {
	private final MessageBank mb;
	private File contact_dir;
	private static final int POLL_AHEAD = 3;
	private static int PASSES_PER_DAY = 3;
	private static int MAX_DAYS_BACK = 30;

	NIMFetcher(MessageBank m, File ctdir) {
		this.mb = m;
		this.contact_dir = ctdir;
	}
	
	public void fetch() {
		NIMContact contact = new NIMContact(this.contact_dir);
		
		int i;
		for (i = 1 - MAX_DAYS_BACK; i <= 0; i++) {
			String datestr = DateStringFactory.getOffsetKeyString(i);
			MailLog log = contact.getLog(datestr);
			
			if (log.getPasses() < PASSES_PER_DAY) {
				this.fetch_day(contact, log, datestr);
				// don't count passes for today since more
				// mail may arrive
				if (i < 0) log.incPasses();
			}
		}
		
		TimeZone gmt = TimeZone.getTimeZone("GMT");
		Calendar cal = Calendar.getInstance(gmt);
		cal.setTime(new Date());
		
		cal.add(Calendar.DAY_OF_MONTH, 0 - MAX_DAYS_BACK);
		contact.pruneLogs(cal.getTime());
	}
	
	private void fetch_day(NIMContact contact, MailLog log, String date) {
		HighLevelFCPClient fcpcli;
		fcpcli = new HighLevelFCPClient();
		
		String keybase;
		try {
			keybase = contact.getKey() + date + "-";
		} catch (IOException ioe) {
			// Jinkies, Scoob! No key!
			return;
		}
		
		int startnum = log.getNextMessageId();
		
		for (int i = startnum; i < startnum + POLL_AHEAD; i++) {
			System.out.println("trying to fetch "+keybase+i);
			
			File result = fcpcli.fetch(keybase+i);
			
			if (result != null) {
				System.out.println(keybase+i+": got message!");
				try {
					this.storeMessage(new BufferedReader(new FileReader(result)), this.mb);
					result.delete();
					log.addMessage(i, "received");
				} catch (IOException ioe) {
					continue;
				}
			} else {
				System.out.println(keybase+i+": no message.");
			}
		}
	}
}