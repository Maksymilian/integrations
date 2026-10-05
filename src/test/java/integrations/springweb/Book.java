package integrations.springweb;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

record Book(
        @Pattern(regexp = "\\d{13}", message = "must be 13 digits") String isbn,
        @NotBlank String title,
        @NotBlank String author,
        @PositiveOrZero int year) {
}
