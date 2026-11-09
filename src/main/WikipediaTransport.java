package main;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.*;
import static main.PackCatalog.require;

/** Narrow, pinned-address TLS transport. No proxy, cookies, auth, URL imports or HTTP redirects. */
final class WikipediaTransport implements WikiApi.Transport {
    interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    private final Resolver resolver;
    WikipediaTransport() { this(InetAddress::getAllByName); }
    WikipediaTransport(Resolver resolver) { this.resolver = resolver; }

    static void destination(URI uri) throws IOException {
        require("https".equals(uri.getScheme()) && "en.wikipedia.org".equals(uri.getHost())
                && uri.getRawAuthority().equals("en.wikipedia.org") && uri.getUserInfo() == null
                && uri.getPort() == -1 && "/w/api.php".equals(uri.getRawPath()) && uri.getFragment() == null
                && uri.getRawQuery() != null && uri.toASCIIString().length() <= 4096,
                "Only the English Wikipedia Action API endpoint is permitted: " + uri);
    }
    static boolean global(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress(); int a = b[0] & 255, c = b[1] & 255;
        if (b.length == 4) return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && c >= 64 && c <= 127) && !(a == 169 && c == 254)
                && !(a == 172 && c >= 16 && c <= 31) && !(a == 192 && (c == 0 || c == 168))
                && !(a == 198 && (c == 18 || c == 19 || c == 51)) && !(a == 203 && c == 0);
        // IPv6 is deliberately limited to ordinary global unicast; reject transition/documentation ranges.
        return b.length == 16 && (a & 0xe0) == 0x20 && !(a == 0x20 && c == 1 && (b[2] & 255) == 0)
                && !(a == 0x20 && c == 1 && (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)
                && !(a == 0x3f && c == 0xff && (b[2] & 0xf0) == 0)
                && !(a == 0x20 && c == 2);
    }
    @Override public WikiApi.Response get(URI uri, String userAgent, WikiApi.Budget budget) throws IOException {
        destination(uri); budget.check();
        long end = Math.min(budget.deadline, System.nanoTime() + Duration.ofSeconds(15).toNanos());
        FutureTask<InetAddress[]> lookup = new FutureTask<>(() -> resolver.resolve(uri.getHost()));
        Thread.ofVirtual().start(lookup);
        InetAddress[] addresses;
        try {
            while (true) {
                budget.check(); require(System.nanoTime() < end, "DNS/request deadline exceeded");
                try { addresses = lookup.get(100, TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException waiting) { /* Poll cancellation as well as the request deadline. */ }
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Cancelled during DNS"); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof IOException io) throw io;
            throw new IOException("DNS resolution failed", error.getCause());
        }
        finally { lookup.cancel(true); }
        require(addresses.length > 0, "DNS returned no addresses");
        for (InetAddress address : addresses) require(global(address), "Rejected non-global resolved destination: " + address);
        try (Socket tcp = new Socket()) {
            tcp.connect(new InetSocketAddress(addresses[0], 443), millis(end));
            try (SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(tcp, "en.wikipedia.org", 443, true)) {
                SSLParameters parameters = tls.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName("en.wikipedia.org"))); tls.setSSLParameters(parameters);
                tls.setSoTimeout(millis(end)); tls.startHandshake(); budget.check();
                String request = "GET " + uri.getRawPath() + "?" + uri.getRawQuery() + " HTTP/1.1\r\nHost: en.wikipedia.org\r\n"
                        + "User-Agent: " + userAgent + "\r\nAccept: application/json\r\nAccept-Encoding: gzip\r\nConnection: close\r\n\r\n";
                tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                tls.setSoTimeout(250);
                InputStream polling = new FilterInputStream(tls.getInputStream()) {
                    @Override public int read() throws IOException {
                        while (true) {
                            budget.check(); require(System.nanoTime() < end, "Request deadline exceeded");
                            try { return super.read(); } catch (SocketTimeoutException waiting) { /* Poll bounded body reads. */ }
                        }
                    }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        while (true) {
                            budget.check(); require(System.nanoTime() < end, "Request deadline exceeded");
                            try { return in.read(b, off, len); } catch (SocketTimeoutException waiting) { /* Poll. */ }
                        }
                    }
                };
                return decode(polling, budget);
            }
        }
    }
    private static int millis(long end) throws IOException {
        long value = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()); require(value > 0, "Request deadline exceeded");
        return (int) Math.min(15000, value);
    }
    static WikiApi.Response decode(InputStream raw, WikiApi.Budget budget) throws IOException {
        InputStream input = new FilterInputStream(raw) {
            int count;
            private void add(int n) throws IOException {
                budget.check(); count += n; budget.bytes(n); require(count <= WikiApi.MAX_RESPONSE, "Response exceeds transferred byte limit (including HTTP framing)");
            }
            @Override public int read() throws IOException { int v = in.read(); if (v >= 0) add(1); return v; }
            @Override public int read(byte[] b, int o, int n) throws IOException { int v = in.read(b, o, n); if (v > 0) add(v); return v; }
        };
        String status = line(input); require(status.matches("HTTP/1\\.[01] [1-5][0-9]{2} .*"), "Malformed HTTP status");
        int code = Integer.parseInt(status.substring(9, 12)); Map<String, String> headers = new HashMap<>();
        int headerBytes = status.length();
        for (String line; !(line = line(input)).isEmpty();) {
            headerBytes += line.length(); require(headerBytes <= 16384, "Oversized HTTP headers");
            int colon = line.indexOf(':'); require(colon > 0, "Malformed HTTP header");
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
            require(name.matches("[a-z0-9-]+"), "Malformed HTTP header name");
            require(!headers.containsKey(name) || !Set.of("content-length", "transfer-encoding", "content-type", "content-encoding", "location", "retry-after").contains(name),
                    "Duplicate framing/control HTTP header: " + name);
            // Repeated Set-Cookie and other advisory fields are legal; cookies are never stored or sent.
            headers.putIfAbsent(name, value);
        }
        if (code >= 300 && code < 400) {
            String target = headers.getOrDefault("location", "");
            try { destination(URI.create("https://en.wikipedia.org/w/api.php").resolve(target)); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid HTTP redirect", error); }
            throw new IOException("HTTP redirects are not permitted; inspect endpoint/policy: " + target);
        }
        require(!headers.containsKey("transfer-encoding") || !headers.containsKey("content-length"), "Ambiguous HTTP framing");
        InputStream framed = input;
        if (headers.containsKey("transfer-encoding")) {
            require(headers.get("transfer-encoding").equalsIgnoreCase("chunked"), "Unsupported transfer encoding");
            framed = new Chunked(input);
        } else if (headers.containsKey("content-length")) {
            long length;
            try { length = Long.parseLong(headers.get("content-length")); } catch (NumberFormatException e) { throw new IOException("Invalid Content-Length", e); }
            require(length >= 0 && length <= WikiApi.MAX_RESPONSE, "Response exceeds transferred byte limit");
            framed = new Fixed(input, length);
        }
        String encoding = headers.getOrDefault("content-encoding", "identity");
        require(encoding.equals("identity") || encoding.equals("gzip"), "Unsupported content encoding");
        InputStream plain = encoding.equals("gzip") ? new GZIPInputStream(framed) : framed;
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] block = new byte[8192];
        for (int n; (n = plain.read(block)) != -1;) {
            budget.check(); require(out.size() + n <= WikiApi.MAX_RESPONSE, "Response exceeds decompressed byte limit"); out.write(block, 0, n);
        }
        if (code == 200) require(headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT).startsWith("application/json"), "Expected JSON response");
        return new WikiApi.Response(code, headers, out.toByteArray());
    }
    private static String line(InputStream input) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int c; (c = input.read()) != -1;) {
            require(result.length() < 8192, "Oversized HTTP line");
            if (c == '\r') { require(input.read() == '\n', "Malformed HTTP newline"); return result.toString(); }
            require(c >= 32 && c <= 126 || c == '\t', "Malformed HTTP line"); result.append((char) c);
        }
        throw new EOFException("Truncated HTTP headers/chunk");
    }
    private static final class Fixed extends InputStream {
        final InputStream in; long remaining;
        Fixed(InputStream in, long remaining) { this.in = in; this.remaining = remaining; }
        @Override public int read() throws IOException {
            if (remaining == 0) return -1; int value = in.read(); if (value < 0) throw new EOFException("Truncated HTTP body"); remaining--; return value;
        }
        @Override public int read(byte[] b, int o, int n) throws IOException {
            if (remaining == 0) return -1; int value = in.read(b, o, (int) Math.min(n, remaining));
            if (value < 0) throw new EOFException("Truncated HTTP body"); remaining -= value; return value;
        }
    }
    private static final class Chunked extends InputStream {
        final InputStream in; long remaining; boolean end, separator;
        Chunked(InputStream in) { this.in = in; }
        @Override public int read() throws IOException {
            if (end) return -1;
            if (remaining == 0) {
                if (separator) require(line(in).isEmpty(), "Invalid chunk separator");
                String size = line(in); require(size.matches("[a-fA-F0-9]{1,8}"), "Invalid/unsupported chunk size");
                remaining = Long.parseLong(size, 16); require(remaining <= WikiApi.MAX_RESPONSE, "Oversized HTTP chunk");
                if (remaining == 0) { require(line(in).isEmpty(), "HTTP trailers are not supported"); end = true; return -1; }
                separator = true;
            }
            int value = in.read(); if (value < 0) throw new EOFException("Truncated HTTP chunk"); remaining--; return value;
        }
        @Override public int read(byte[] b, int o, int n) throws IOException {
            int first = read(); if (first < 0) return -1; b[o] = (byte) first;
            if (n == 1 || remaining == 0) return 1;
            int count = in.read(b, o + 1, (int) Math.min(n - 1, remaining));
            if (count < 0) throw new EOFException("Truncated HTTP chunk"); remaining -= count; return count + 1;
        }
    }
}
