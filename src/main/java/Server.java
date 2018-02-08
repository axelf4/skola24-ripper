import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class Server extends NanoHTTPD {
	private static final int PORT = 89;

	public Server() throws IOException {
		super(PORT);
		start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
		System.out.println("\nRunning! Point your browsers to http://localhost:" + PORT + "/\n");
	}

	public static void main(String[] args) {
		try {
			new Server();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public Response serve(IHTTPSession session) {
		Map<String, List<String>> params = session.getParameters();
		List<String> signatures = params.get("signature");
		String signature = signatures == null || signatures.isEmpty() ? null : signatures.get(0);
		if (signature == null) {
			return newFixedLengthResponse("<html><body><form action=\"?\" method=\"get\">" +
					"<label>Skriv in personnummer: <input type=\"text\" name=\"signature\"></label>" +
					"</form></body></html>");
		}

		try {
			String msg = TimetableParser.getICalendarString(signature);
			Response res = newFixedLengthResponse(Response.Status.OK, "text/calendar", msg);
			res.addHeader("Content-Disposition", "attachment; filename=\"cal.ics\"");
			return res;
		} catch (IOException e) {
			e.printStackTrace();
		}

		return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Error.\n");
	}
}
