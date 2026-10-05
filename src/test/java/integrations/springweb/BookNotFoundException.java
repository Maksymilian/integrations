package integrations.springweb;

final class BookNotFoundException extends RuntimeException {

    private final String isbn;

    BookNotFoundException(String isbn) {
        super("No book with ISBN " + isbn);
        this.isbn = isbn;
    }

    String isbn() {
        return isbn;
    }
}
