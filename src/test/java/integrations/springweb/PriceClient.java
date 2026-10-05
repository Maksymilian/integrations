package integrations.springweb;

/** The other service. A seam, so tests can replace the HTTP call with a Mockito mock. */
interface PriceClient {

    Price fetch(String isbn);
}
