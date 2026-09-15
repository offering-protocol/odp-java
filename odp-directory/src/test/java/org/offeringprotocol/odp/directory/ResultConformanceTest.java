package org.offeringprotocol.odp.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.PaymentOption;

class ResultConformanceTest {
    private static final String OPERATIONS = """
            "operations":[{"authentication":"not-required","name":"get-offering"},
                          {"authentication":"not-required","name":"list-offerings"}]""";
    private static final String RECORD = """
            {"service_origin":"https://plants.example","name":"Plants","description":"Plant store",
             "language":"en","localizations":["en"],"keywords":["plants"],
             "indexed_at":"2026-08-28T00:00:00Z",%s}""".formatted(OPERATIONS);

    private static DirectoryModels.SearchPage page(String items, String extra) {
        return DirectoryClient.decodeSearchPage("{\"items\":[" + items + "]" + extra + "}");
    }

    @Test
    void readsAWellFormedPage() {
        DirectoryModels.SearchPage decoded = page(
                RECORD,
                ",\"next\":\"/v1/services/search?cursor=c2\",\"facets\":{"
                        + "\"keywords\":[{\"value\":\"plants\",\"count\":4}],"
                        + "\"enrollment\":[{\"value\":{\"name\":\"api-key\"},\"count\":1}],"
                        + "\"operations\":[{\"value\":{\"authentication\":\"not-required\","
                        + "\"name\":\"get-offering\"},\"count\":2}],"
                        + "\"payments\":[{\"value\":{\"authentication\":\"required\",\"name\":\"mpp\"},"
                        + "\"count\":1}],"
                        + "\"payment_options\":[{\"value\":{\"name\":\"mpp\",\"option\":\"base\"},"
                        + "\"count\":1}]},\"total\":9");

        assertEquals(1, decoded.items().size());
        DirectoryModels.Service service = decoded.items().get(0);
        assertEquals("https://plants.example", service.serviceOrigin());
        assertEquals("Plants", service.name());
        assertEquals(Instant.parse("2026-08-28T00:00:00Z"), service.indexedAt());
        assertEquals("/v1/services/search?cursor=c2", decoded.next());
        assertEquals(List.of("plants"), service.keywords());
        assertEquals(4, decoded.facets().keywords().get(0).count());
        assertEquals("api-key", decoded.facets().enrollment().get(0).value().name());
        assertEquals(
                OdpOperation.GET_OFFERING,
                decoded.facets().operations().get(0).value().name());
        assertEquals("mpp", decoded.facets().payments().get(0).value().name());
        assertEquals(
                PaymentOption.BASE,
                decoded.facets().paymentOptions().get(0).value().option());
        assertTrue(decoded.issues().isEmpty());
        assertEquals("9", decoded.additional().get("total").toString());
    }

    @Test
    void readsAPageThatCarriesNoResults() {
        assertTrue(DirectoryClient.decodeSearchPage("{}").items().isEmpty());
        assertTrue(DirectoryClient.decodeSearchPage("{\"items\":null}").items().isEmpty());
        assertTrue(DirectoryClient.decodeSearchPage("{\"items\":[]}").issues().isEmpty());
    }

    /** One unusable result does not discard the page it arrived on; the rest are still handed over. */
    @Test
    void dropsAnUnusableResultAndKeepsTheRestOfThePage() {
        DirectoryModels.SearchPage decoded =
                page(RECORD.replace("https://plants.example", "http://plants.example") + "," + RECORD, "");

        assertEquals(1, decoded.items().size());
        assertEquals("https://plants.example", decoded.items().get(0).serviceOrigin());
        assertEquals(1, decoded.issues().size());
        assertEquals(0, decoded.issues().get(0).index());
        assertEquals(
                "Directory result service_origin must be a canonical HTTPS origin",
                decoded.issues().get(0).message());
    }

    /** An issue names where the result sat in the page as sent, not where it sat after the drops. */
    @Test
    void reportsEachIssueAtItsPositionInThePageAsSent() {
        String broken = RECORD.replace("https://plants.example", "https://127.0.0.1");
        DirectoryModels.SearchPage decoded = page(String.join(",", broken, RECORD, broken, RECORD, broken), "");

        assertEquals(2, decoded.items().size());
        assertEquals(
                List.of(0, 2, 4),
                decoded.issues().stream().map(DirectoryModels.Issue::index).toList());
    }

    /** A result claims to summarize a Service, so it is held to the shape of the document it summarizes. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"name\":\"Plants\",",
                "\"description\":\"Plant store\",",
                "\"language\":\"en\",",
                "\"localizations\":[\"en\"],"
            })
    void dropsAResultThatIsNotShapedLikeTheServiceItSummarizes(String member) {
        assertEquals(0, page(RECORD.replace(member, ""), "").items().size());
    }

    @Test
    void dropsAResultThatCannotNameItsService() {
        assertEquals(List.of("Directory result must be a JSON object"), messages(page("\"plants\"", "")));
        assertEquals(
                List.of("Directory result service_origin is missing"),
                messages(page(RECORD.replace("\"https://plants.example\"", "7"), "")));
        assertEquals(
                List.of("Directory result service_origin is missing"),
                messages(page(RECORD.replace("\"service_origin\":\"https://plants.example\",", ""), "")));
    }

    /** A directory summarizes a Service; it does not serve the Service's own document. */
    @Test
    void dropsTheMembersADirectoryCannotVouchFor() {
        DirectoryModels.SearchPage decoded = page(RECORD.replace("\"name\":\"Plants\",", """
                        "name":"Plants","odp_version":"1.0","branding":{"logo_url":"https://plants.example/logo.png"},
                        "http":{"endpoint_base":"/odp"},"mcp":{"endpoint":"https://plants.example/mcp"},
                        "payment_origins":["https://pay.example"],"search_capabilities":{"offerings":{"query":true}},"""), "");

        assertEquals(1, decoded.items().size());
        assertTrue(
                decoded.items().get(0).additional().isEmpty(),
                () -> decoded.items().get(0).additional().toString());
    }

    /** The issues a caller reads are the ones this client found, not ones the directory wrote itself. */
    @Test
    void ignoresAnIssuesMemberTheDirectorySentItself() {
        DirectoryModels.SearchPage decoded = page(RECORD, ",\"issues\":[{\"index\":0,\"message\":\"invented\"}]");

        assertTrue(decoded.issues().isEmpty());
        assertNull(decoded.additional().get("issues"));
    }

    @Test
    void keepsAnUnknownMemberOfAResultTheDirectoryCanVouchFor() {
        DirectoryModels.SearchPage decoded =
                page(RECORD.replace("\"name\":\"Plants\",", "\"name\":\"Plants\",\"rank\":3,"), "");
        assertEquals("3", decoded.items().get(0).additional().get("rank").toString());
    }

    @Test
    void refusesAPageItCannotRead() {
        for (String body : List.of("not json", "[1,2,3]", "null", "\"page\"")) {
            assertEquals(
                    "Directory response is invalid",
                    assertThrows(IllegalArgumentException.class, () -> DirectoryClient.decodeSearchPage(body))
                            .getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> DirectoryClient.decodeSearchPage("{\"next\":7}"));
        assertThrows(IllegalArgumentException.class, () -> DirectoryClient.decodeSearchPage("{\"facets\":7}"));
        assertThrows(IllegalArgumentException.class, () -> DirectoryClient.decodeSearchPage(""));
        assertNull(DirectoryClient.decodeSearchPage("{\"next\":null}").next());
    }

    @Test
    void readsSuggestionsInTheOrderTheyArrived() {
        assertEquals(
                List.of("plants", "planters"),
                DirectoryClient.decodeSuggestions("{\"items\":[\"plants\",\"planters\"]}", 25));
    }

    /** A directory answering with more than was asked for is answering a different request. */
    @Test
    void handsBackNoMoreSuggestionsThanWereAskedFor() {
        assertEquals(List.of("a", "b"), DirectoryClient.decodeSuggestions("{\"items\":[\"a\",\"b\",\"c\",\"d\"]}", 2));
    }

    @Test
    void keepsOneCopyOfARepeatedSuggestion() {
        assertEquals(List.of("a", "b"), DirectoryClient.decodeSuggestions("{\"items\":[\"a\",\"b\",\"a\"]}", 25));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json",
                "[\"a\"]",
                "null",
                "{}",
                "{\"items\":null}",
                "{\"items\":\"a\"}",
                "{\"items\":[7]}",
                "{\"items\":[\"  \"]}",
                "{\"items\":[null]}",
                ""
            })
    void refusesSuggestionsItCannotRead(String body) {
        assertEquals(
                "Directory suggestions response is invalid",
                assertThrows(IllegalArgumentException.class, () -> DirectoryClient.decodeSuggestions(body, 25))
                        .getMessage());
    }

    @Test
    void refusesASuggestionLongerThanAPrefixCouldEverBe() {
        String body = "{\"items\":[\"" + "a".repeat(129) + "\"]}";
        assertThrows(IllegalArgumentException.class, () -> DirectoryClient.decodeSuggestions(body, 25));
    }

    private static List<String> messages(DirectoryModels.SearchPage page) {
        return page.issues().stream().map(DirectoryModels.Issue::message).toList();
    }
}
