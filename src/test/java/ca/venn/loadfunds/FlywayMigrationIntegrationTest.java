package ca.venn.loadfunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import ca.venn.loadfunds.model.velocity.RejectionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the Flyway migration produces the schema the application relies on:
 * table shape, constraints, and the invariants the DB is meant to enforce on its
 * own. Deliberately uses raw JdbcTemplate rather than repositories — this asserts
 * what the database guarantees, not what the mapping happens to do.
 */
@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:flyway-migration-test;DB_CLOSE_DELAY=-1;",
        "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@Transactional
class FlywayMigrationIntegrationTest {

    private static final String CUSTOMER_ID = "customer-1";
    private static final String OTHER_CUSTOMER_ID = "customer-2";

    private static final OffsetDateTime PROCESSED_AT =
        OffsetDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void insertCustomers() {
        // load_funds_attempt has an FK to customer, so the parent rows must exist
        // before any attempt insert. Rolled back with the test transaction.
        insertCustomer(CUSTOMER_ID);
        insertCustomer(OTHER_CUSTOMER_ID);
    }

    // --- Schema shape --------------------------------------------------------

    @Test
    void createsExpectedTables() {
        Integer tableCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = 'PUBLIC'
                  AND TABLE_NAME IN (
                      'CUSTOMER',
                      'LOAD_FUNDS_ATTEMPT'
                  )
                """,
            Integer.class
        );

        assertThat(tableCount).isEqualTo(2);
    }

    @Test
    void createsExpectedColumnsInOrder() {
        assertThat(columnNames("CUSTOMER"))
            .containsExactly("ID", "CREATED_AT");
        assertThat(columnNames("LOAD_FUNDS_ATTEMPT"))
            .containsExactly(
                "ID",
                "CUSTOMER_ID",
                "LOAD_FUNDS_ID",
                "AMOUNT_CENTS",
                "STATUS",
                "REJECTION_REASON",
                "PROCESSED_AT",
                "CREATED_AT"
            );
    }

    @Test
    void usesExpectedIdentifierAndEnumColumnLengths() {
        assertThat(characterLength("CUSTOMER", "ID")).isEqualTo(100L);
        assertThat(characterLength("LOAD_FUNDS_ATTEMPT", "CUSTOMER_ID")).isEqualTo(100L);
        assertThat(characterLength("LOAD_FUNDS_ATTEMPT", "LOAD_FUNDS_ID")).isEqualTo(100L);
        assertThat(characterLength("LOAD_FUNDS_ATTEMPT", "STATUS")).isEqualTo(20L);
        assertThat(characterLength("LOAD_FUNDS_ATTEMPT", "REJECTION_REASON")).isEqualTo(50L);
    }

    @Test
    void createsVelocityIndexWithExpectedColumnOrder() {
        List<String> indexColumns = jdbcTemplate.queryForList(
            """
                SELECT COLUMN_NAME
                FROM INFORMATION_SCHEMA.INDEX_COLUMNS
                WHERE INDEX_SCHEMA = 'PUBLIC'
                  AND INDEX_NAME = 'IDX_LOAD_FUNDS_ATTEMPT_VELOCITY'
                ORDER BY ORDINAL_POSITION
                """,
            String.class
        );

        assertThat(indexColumns).containsExactly("CUSTOMER_ID", "STATUS", "PROCESSED_AT");
    }

    @Test
    void recordsSuccessfulV1MigrationInFlywayHistory() {
        Integer migrationCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM "flyway_schema_history"
                WHERE "version" = '1'
                  AND "script" = 'V1__create_load_funds_tables.sql'
                  AND "success" = TRUE
                """,
            Integer.class
        );

        assertThat(migrationCount).isEqualTo(1);
    }

    @Test
    void generatesInternalIdForLoadFundsAttempt() {
        insertAttempt(CUSTOMER_ID, "load-1", 12_345L, "ACCEPTED", null);

        Long id = jdbcTemplate.queryForObject(
            """
                SELECT id
                FROM load_funds_attempt
                WHERE customer_id = ?
                  AND load_funds_id = ?
                """,
            Long.class,
            CUSTOMER_ID,
            "load-1"
        );

        assertThat(id).isNotNull().isPositive();
    }

    @Test
    void defaultsCreatedAt() {
        insertAttempt(CUSTOMER_ID, "load-1", 12_345L, "ACCEPTED", null);

        OffsetDateTime createdAt = jdbcTemplate.queryForObject(
            """
                SELECT created_at
                FROM load_funds_attempt
                WHERE customer_id = ?
                  AND load_funds_id = ?
                """,
            OffsetDateTime.class,
            CUSTOMER_ID,
            "load-1"
        );

        assertThat(createdAt).isNotNull();
    }

    @Test
    void defaultsCustomerCreatedAt() {
        OffsetDateTime createdAt = jdbcTemplate.queryForObject(
            "SELECT created_at FROM customer WHERE id = ?",
            OffsetDateTime.class,
            CUSTOMER_ID
        );

        assertThat(createdAt).isNotNull();
    }

    @Test
    void storesAllAttemptValues() {
        insertAttempt(CUSTOMER_ID, "load-1", 12_345L, "DECLINED", "WEEKLY_AMOUNT_EXCEEDED");

        var stored = jdbcTemplate.queryForMap(
            """
                SELECT customer_id, load_funds_id, amount_cents, status, rejection_reason, processed_at
                FROM load_funds_attempt
                WHERE customer_id = ? AND load_funds_id = ?
                """,
            CUSTOMER_ID,
            "load-1"
        );

        assertThat(stored)
            .containsEntry("CUSTOMER_ID", CUSTOMER_ID)
            .containsEntry("LOAD_FUNDS_ID", "load-1")
            .containsEntry("AMOUNT_CENTS", 12_345L)
            .containsEntry("STATUS", "DECLINED")
            .containsEntry("REJECTION_REASON", "WEEKLY_AMOUNT_EXCEEDED");
        assertThat(stored.get("PROCESSED_AT")).isEqualTo(PROCESSED_AT);
    }

    // --- Happy paths ---------------------------------------------------------

    @Test
    void acceptsValidAcceptedLoadFundsAttempt() {
        int inserted = insertAttempt(CUSTOMER_ID, "load-1", 12_345L, "ACCEPTED", null);

        assertThat(inserted).isEqualTo(1);
        assertThat(statusOf(CUSTOMER_ID, "load-1")).isEqualTo("ACCEPTED");
    }

    @Test
    void acceptsValidDeclinedLoadFundsAttempt() {
        int inserted = insertAttempt(
            CUSTOMER_ID, "load-1", 12_345L, "DECLINED", "DAILY_AMOUNT_EXCEEDED");

        assertThat(inserted).isEqualTo(1);
        assertThat(statusOf(CUSTOMER_ID, "load-1")).isEqualTo("DECLINED");
    }

    @ParameterizedTest
    @EnumSource(RejectionReason.class)
    void acceptsEveryDeclaredRejectionReason(RejectionReason reason) {
        int inserted = insertAttempt(CUSTOMER_ID, "load-" + reason.name(), 12_345L, "DECLINED", reason.name());

        assertThat(inserted).isEqualTo(1);
    }

    // --- uq_load_funds_attempt_customer_load ---------------------------------

    @Test
    void enforcesUniqueCustomerAndLoadFundsId() {
        insertAttempt(CUSTOMER_ID, "load-1", 12_345L, "ACCEPTED", null);

        assertThatThrownBy(() ->
                               insertAttempt(CUSTOMER_ID, "load-1", 5_000L, "DECLINED", "DAILY_AMOUNT_EXCEEDED")
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameLoadFundsIdForDifferentCustomers() {
        insertAttempt(CUSTOMER_ID, "load-1", 12_345L, "ACCEPTED", null);

        int inserted = insertAttempt(OTHER_CUSTOMER_ID, "load-1", 12_345L, "ACCEPTED", null);

        assertThat(inserted).isEqualTo(1);
    }

    // --- fk_load_funds_attempt_customer --------------------------------------

    @Test
    void rejectsAttemptForUnknownCustomer() {
        assertThatThrownBy(() ->
                               insertAttempt("does-not-exist", "load-1", 12_345L, "ACCEPTED", null)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preventsDeletingCustomerWithAttempts() {
        insertAttempt(CUSTOMER_ID, "load-1", 12_345L, "ACCEPTED", null);

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM customer WHERE id = ?", CUSTOMER_ID))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- chk_amount_positive -------------------------------------------------

    @Test
    void rejectsZeroAmount() {
        assertThatThrownBy(() ->
                               insertAttempt(CUSTOMER_ID, "load-1", 0L, "ACCEPTED", null)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() ->
                               insertAttempt(CUSTOMER_ID, "load-1", -100L, "ACCEPTED", null)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- chk_status ----------------------------------------------------------

    @Test
    void rejectsUnknownAttemptStatus() {
        assertThatThrownBy(() ->
                               insertAttempt(CUSTOMER_ID, "load-1", 12_345L, "UNKNOWN", null)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsProcessingAttemptStatus() {
        assertThatThrownBy(() ->
                               insertAttempt(CUSTOMER_ID, "load-1", 12_345L, "PROCESSING", null)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer_id", "load_funds_id", "amount_cents", "status", "processed_at"})
    void rejectsNullRequiredAttemptColumn(String column) {
        assertThatThrownBy(() -> insertAttemptWithNull(column))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- chk_rejection_reason ------------------------------------------------

    @Test
    void declinedAttemptMustHaveRejectionReason() {
        assertThatThrownBy(() ->
                               insertAttempt(CUSTOMER_ID, "load-1", 12_345L, "DECLINED", null)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptedAttemptMustNotHaveRejectionReason() {
        assertThatThrownBy(() ->
                               insertAttempt(CUSTOMER_ID, "load-1", 12_345L, "ACCEPTED", "DAILY_AMOUNT_EXCEEDED")
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownRejectionReason() {
        assertThatThrownBy(() ->
                               insertAttempt(CUSTOMER_ID, "load-1", 12_345L, "DECLINED", "UNKNOWN")
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- customer ------------------------------------------------------------

    @Test
    void enforcesUniqueCustomerId() {
        assertThatThrownBy(() -> insertCustomer(CUSTOMER_ID))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNullCustomerId() {
        assertThatThrownBy(() -> insertCustomer(null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesCustomerIdLength() {
        assertThat(insertCustomer("c".repeat(100))).isEqualTo(1);

        assertThatThrownBy(() -> insertCustomer("c".repeat(101)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesLoadFundsIdLength() {
        assertThat(insertAttempt(CUSTOMER_ID, "l".repeat(100), 12_345L, "ACCEPTED", null)).isEqualTo(1);

        assertThatThrownBy(() ->
                               insertAttempt(CUSTOMER_ID, "l".repeat(101), 12_345L, "ACCEPTED", null)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- helpers -------------------------------------------------------------

    private int insertCustomer(String customerId) {
        return jdbcTemplate.update("INSERT INTO customer (id) VALUES (?)", customerId);
    }

    private int insertAttempt(
        String customerId,
        String loadFundsId,
        long amountCents,
        String status,
        String rejectionReason
    ) {
        return jdbcTemplate.update(
            """
                INSERT INTO load_funds_attempt (
                    customer_id,
                    load_funds_id,
                    amount_cents,
                    status,
                    rejection_reason,
                    processed_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            customerId,
            loadFundsId,
            amountCents,
            status,
            rejectionReason,
            PROCESSED_AT
        );
    }

    private int insertAttemptWithNull(String column) {
        return jdbcTemplate.update(
            """
                INSERT INTO load_funds_attempt (
                    customer_id,
                    load_funds_id,
                    amount_cents,
                    status,
                    rejection_reason,
                    processed_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            column.equals("customer_id") ? null : CUSTOMER_ID,
            column.equals("load_funds_id") ? null : "load-null-" + column,
            column.equals("amount_cents") ? null : 12_345L,
            column.equals("status") ? null : "ACCEPTED",
            null,
            column.equals("processed_at") ? null : PROCESSED_AT
        );
    }

    private List<String> columnNames(String tableName) {
        return jdbcTemplate.queryForList(
            """
                SELECT COLUMN_NAME
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = 'PUBLIC'
                  AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """,
            String.class,
            tableName
        );
    }

    private Long characterLength(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
            """
                SELECT CHARACTER_MAXIMUM_LENGTH
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = 'PUBLIC'
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """,
            Long.class,
            tableName,
            columnName
        );
    }

    private String statusOf(String customerId, String loadFundsId) {
        return jdbcTemplate.queryForObject(
            """
                SELECT status
                FROM load_funds_attempt
                WHERE customer_id = ?
                  AND load_funds_id = ?
                """,
            String.class,
            customerId,
            loadFundsId
        );
    }
}
