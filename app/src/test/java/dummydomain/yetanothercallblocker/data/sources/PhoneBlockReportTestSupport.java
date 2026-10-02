package dummydomain.yetanothercallblocker.data.sources;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Fake transport and canned answers of the PhoneBlock server for the reporting tests.
 * The bodies are the ones the server sends (see {@code RateServlet},
 * {@code PersonalizationServlet} and {@code ServletUtil} of the PhoneBlock sources).
 */
final class PhoneBlockReportTestSupport {

    /** {@code RateServlet}: 200, text/plain. */
    static final String RATE_OK = "Rating recorded.";
    /** {@code RateServlet} for an unparsable number: 400 via {@code ServletUtil.sendError}. */
    static final String RATE_INVALID = "Invalid phone number.";
    /** {@code ServletUtil.sendAuthenticationRequest}: 401 (also for a key without "rate"). */
    static final String AUTH_REQUIRED = "Please provide login credentials.";
    /** {@code PersonalizationServlet.doDelete} for a number that isn't listed: 404. */
    static final String NOT_IN_LIST = "Phone number not found in personalization list";

    /** One recorded request. */
    static final class Request {
        final String method;
        final String url;
        final Map<String, String> headers;
        final String contentType;
        final String body;

        Request(String method, String url, Map<String, String> headers,
                String contentType, String body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.contentType = contentType;
            this.body = body;
        }
    }

    /** Returns canned responses (or a failure) and records the requests. */
    static final class FakeTransport implements PhoneBlockClient.HttpTransport {
        final Deque<Object> responses = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();

        FakeTransport respond(int code, String body) {
            responses.add(PhoneBlockClient.HttpResponse.of(code, body));
            return this;
        }

        FakeTransport fail(IOException e) {
            responses.add(e);
            return this;
        }

        @Override
        public PhoneBlockClient.HttpResponse get(String url, Map<String, String> headers)
                throws IOException {
            return send("GET", url, headers, null, null);
        }

        @Override
        public PhoneBlockClient.HttpResponse send(String method, String url,
                                                  Map<String, String> headers,
                                                  String contentType, String body)
                throws IOException {
            requests.add(new Request(method, url, headers, contentType, body));
            Object response = responses.poll();
            if (response == null) throw new AssertionError("Unexpected request " + url);
            if (response instanceof IOException) throw (IOException) response;
            return (PhoneBlockClient.HttpResponse) response;
        }
    }

    private PhoneBlockReportTestSupport() {
    }

}
