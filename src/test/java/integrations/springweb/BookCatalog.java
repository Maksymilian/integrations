package integrations.springweb;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In memory, so the full-stack tests share its state for as long as Spring caches the context. */
@Service
class BookCatalog {

    private final Map<String, Book> books = new ConcurrentHashMap<>();

    List<Book> findAll() {
        return books.values().stream().sorted(Comparator.comparing(Book::isbn)).toList();
    }

    Optional<Book> find(String isbn) {
        return Optional.ofNullable(books.get(isbn));
    }

    /** @return false, and no change, if the ISBN is taken */
    boolean add(Book book) {
        return books.putIfAbsent(book.isbn(), book) == null;
    }

    boolean remove(String isbn) {
        return books.remove(isbn) != null;
    }
}
