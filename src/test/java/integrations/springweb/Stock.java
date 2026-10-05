package integrations.springweb;

/** {@code available} is null when the inventory service could not be asked. */
record Stock(String isbn, Integer available) {
}
