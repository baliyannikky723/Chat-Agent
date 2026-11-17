package app.orrery.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class SafetyAndHtmlTest {

    @Test
    void privateAndSpecialAddressesAreNotPublic() throws Exception {
        for (String ip : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
                "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1"}) {
            assertFalse(UrlGuard.isPublic(InetAddress.getByName(ip)), ip);
        }
        assertTrue(UrlGuard.isPublic(InetAddress.getByName("93.184.216.34")));
        assertTrue(UrlGuard.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void unsafeUrlsAreRejectedBeforeAnyFetch() {
        assertThrows(UrlGuard.UnsafeUrlException.class, () -> UrlGuard.check("file:///etc/passwd"));
        assertThrows(UrlGuard.UnsafeUrlException.class, () -> UrlGuard.check("http://localhost:8080/admin"));
        assertThrows(UrlGuard.UnsafeUrlException.class, () -> UrlGuard.check("http://169.254.169.254/latest/meta-data"));
        assertThrows(UrlGuard.UnsafeUrlException.class, () -> UrlGuard.check("http://user:pass@93.184.216.34/"));
        assertThrows(UrlGuard.UnsafeUrlException.class, () -> UrlGuard.check("http://93.184.216.34:22/"));
    }

    @Test
    void extractsMainContentTitleAndPublishDate() {
        String html = """
                <html><head><title>Pricing | Neon</title>
                <meta property="article:published_time" content="2026-11-12T08:00:00Z">
                <script>var tracking = "ignore me";</script><style>.x{}</style></head>
                <body><nav>Home Docs Pricing Login</nav>
                <main><h1>Plans</h1><p>The <b>Launch</b> plan costs &#36;19&nbsp;per month.</p>
                <ul><li>10 GB storage</li><li>Autoscaling</li></ul>
                <table><tr><td>Scale</td><td>$69</td></tr></table>
                <p>""" + "Filler text so the main region is long enough to be preferred. ".repeat(10) + """
                </p></main><footer>© Neon</footer></body></html>""";
        HtmlText.Extracted ex = HtmlText.extract(html, 5000);
        assertEquals("Pricing | Neon", ex.title());
        assertEquals("2026-11-12T08:00:00Z", ex.publishedAt());
        assertTrue(ex.text().contains("The Launch plan costs $19 per month."), ex.text());
        assertTrue(ex.text().contains("- 10 GB storage"));
        assertTrue(ex.text().contains("Scale | $69"));
        assertFalse(ex.text().contains("tracking"));
        assertFalse(ex.text().contains("Login"));
        assertFalse(ex.text().contains("© Neon"));
    }

    @Test
    void stripsTagsAndEntitiesFromSnippets() {
        assertEquals("Neon's Launch plan is $19 – billed monthly", HtmlText.stripTags("<strong>Neon&#39;s</strong> Launch plan is $19 &ndash; billed monthly"));
    }
}
