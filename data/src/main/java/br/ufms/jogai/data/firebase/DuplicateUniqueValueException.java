package br.ufms.jogai.data.firebase;

/**
 * Infrastructure conflict that an entity repository may translate into a domain exception.
 */
public class DuplicateUniqueValueException extends RuntimeException {

    private final String field;

    /**
     * Describes a conflict on one unique field or the reserved ID name.
     * @param field the conflicting field name
     */
    public DuplicateUniqueValueException(String field) {
        super("The value of field " + field + " already belongs to another entity.");
        this.field = field;
    }

    /** @return the conflicting field name, or {@code "id"} for an existing entity ID */
    public String getField() {
        return field;
    }
}
