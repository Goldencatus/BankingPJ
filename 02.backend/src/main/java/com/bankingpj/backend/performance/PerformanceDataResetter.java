package com.bankingpj.backend.performance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;

/** bankingpj_perf의 STEP 16 데이터만 FK 역순으로 정리하는 전용 도구. */
public final class PerformanceDataResetter {

    private static final String PERFORMANCE_DATABASE = "bankingpj_perf";
    private static final int DELETE_BATCH_SIZE = 2_000;

    /** Resetter를 인스턴스화하지 못하게 하고 명시적인 main 진입점만 제공한다. */
    private PerformanceDataResetter() {
    }

    /** 환경변수의 대상 DB를 검증한 뒤 STEP 16 데이터만 초기화한다. */
    public static void main(String[] args) {
        try {
            Map<String, String> environment = System.getenv();
            String databaseName = requiredEnvironment(environment, "DB_NAME");
            validateDatabaseName(databaseName);
            String username = requiredEnvironment(environment, "DB_USERNAME");
            String databasePassword = requiredEnvironment(environment, "DB_PASSWORD");
            String jdbcUrl = "jdbc:mysql://localhost:3306/" + databaseName
                    + "?serverTimezone=UTC&characterEncoding=UTF-8&rewriteBatchedStatements=true";

            System.out.printf(Locale.ROOT, "[SEED RESET] database=%s%n", databaseName);
            ResetResult result;
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, databasePassword)) {
                connection.setAutoCommit(false);
                validateDatabaseName(currentDatabase(connection));
                result = new ResetRunner(connection).reset();
            }
            System.out.printf(Locale.ROOT,
                    "[SEED RESET] completed users=%d accounts=%d transfers=%d ledgerEntries=%d "
                            + "refreshTokens=%d transferIdempotencies=%d%n",
                    result.users(), result.accounts(), result.transfers(), result.ledgerEntries(),
                    result.refreshTokens(), result.transferIdempotencies());
        } catch (IllegalArgumentException exception) {
            System.err.println("[SEED RESET] " + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            // 오류 원인만 출력하여 DB 비밀번호가 로그에 남지 않게 한다.
            System.err.println("[SEED RESET] failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    /** reset 대상이 전용 성능 테스트 DB인지 정확히 확인한다. */
    static void validateDatabaseName(String databaseName) {
        if (!PERFORMANCE_DATABASE.equals(databaseName)) {
            throw new IllegalArgumentException("resetSeedData는 bankingpj_perf DB에서만 실행할 수 있습니다");
        }
    }

    /** 환경변수의 존재만 확인하고 민감한 값은 출력하지 않는다. */
    private static String requiredEnvironment(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("필수 환경변수가 없습니다: " + name);
        }
        return value;
    }

    /** JDBC 연결이 실제로 선택한 DB 이름을 조회한다. */
    private static String currentDatabase(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT DATABASE()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private record ResetResult(long users, long accounts, long transfers, long ledgerEntries,
                               long refreshTokens, long transferIdempotencies) {
    }

    private static final class ResetRunner {
        private static final String USER_MARKER =
                "email LIKE 'step16-%@bankingpj.test' OR email LIKE 'k6-%@bankingpj.test'";
        private final Connection connection;

        /** 하나의 JDBC 연결에서 임시 ID 집합과 삭제 청크를 관리한다. */
        private ResetRunner(Connection connection) {
            this.connection = connection;
        }

        /** 관련 ID를 먼저 고정한 뒤 FK 의존성의 역순으로 데이터를 삭제한다. */
        private ResetResult reset() throws SQLException {
            try {
                prepareTargetIds();
                ensureNoGeneralAccountTransferIsIncluded();
                ensureNoGeneralLedgerIsIncluded();
                ensureNoGeneralIdempotencyIsIncluded();

                long transferIdempotencies = deleteInChunks(
                        "DELETE FROM transfer_idempotencies "
                                + "WHERE user_id IN (SELECT user_id FROM step16_reset_user_ids) "
                                + "OR transfer_id IN (SELECT transfer_id FROM step16_reset_transfer_ids) LIMIT ?");
                long ledgerEntries = deleteInChunks(
                        "DELETE FROM ledger_entries "
                                + "WHERE account_id IN (SELECT account_id FROM step16_reset_account_ids) "
                                + "OR transfer_id IN (SELECT transfer_id FROM step16_reset_transfer_ids) LIMIT ?");
                long transfers = deleteInChunks(
                        "DELETE FROM transfers "
                                + "WHERE transfer_id IN (SELECT transfer_id FROM step16_reset_transfer_ids) LIMIT ?");
                long refreshTokens = deleteInChunks(
                        "DELETE FROM refresh_tokens "
                                + "WHERE user_id IN (SELECT user_id FROM step16_reset_user_ids) LIMIT ?");
                long accounts = deleteInChunks(
                        "DELETE FROM accounts "
                                + "WHERE account_id IN (SELECT account_id FROM step16_reset_account_ids) LIMIT ?");
                long users = deleteInChunks(
                        "DELETE FROM users "
                                + "WHERE user_id IN (SELECT user_id FROM step16_reset_user_ids) LIMIT ?");

                // 일반 데이터의 ID 흐름을 보존하고 Seeder의 MAX(id)+1 재계산을 사용한다.
                return new ResetResult(users, accounts, transfers, ledgerEntries,
                        refreshTokens, transferIdempotencies);
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(exception);
                throw exception;
            }
        }

        /** Seeder 사용자·계좌·이체 ID를 세션 임시 테이블에 고정한다. */
        private void prepareTargetIds() throws SQLException {
            execute("CREATE TEMPORARY TABLE step16_reset_user_ids "
                    + "(user_id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
            execute("CREATE TEMPORARY TABLE step16_reset_account_ids "
                    + "(account_id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
            execute("CREATE TEMPORARY TABLE step16_reset_transfer_ids "
                    + "(transfer_id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");

            executeUpdate("INSERT INTO step16_reset_user_ids (user_id) "
                    + "SELECT user_id FROM users WHERE " + USER_MARKER);
            executeUpdate("INSERT INTO step16_reset_account_ids (account_id) "
                    + "SELECT account_id FROM accounts "
                    + "WHERE user_id IN (SELECT user_id FROM step16_reset_user_ids)");
            executeUpdate("INSERT IGNORE INTO step16_reset_transfer_ids (transfer_id) "
                    + "SELECT t.transfer_id FROM transfers t "
                    + "JOIN step16_reset_account_ids a ON a.account_id = t.from_account_id");
            executeUpdate("INSERT IGNORE INTO step16_reset_transfer_ids (transfer_id) "
                    + "SELECT t.transfer_id FROM transfers t "
                    + "JOIN step16_reset_account_ids a ON a.account_id = t.to_account_id");
            connection.commit();
        }

        /** 일반 계좌와 섞인 이체가 있으면 일반 금융 데이터 보호를 위해 삭제 전에 중단한다. */
        private void ensureNoGeneralAccountTransferIsIncluded() throws SQLException {
            long outgoingToGeneral = count(
                    "SELECT COUNT(*) FROM transfers t "
                            + "JOIN step16_reset_account_ids source_seed ON source_seed.account_id = t.from_account_id "
                            + "JOIN accounts target_account ON target_account.account_id = t.to_account_id "
                            + "LEFT JOIN step16_reset_user_ids target_seed ON target_seed.user_id = target_account.user_id "
                            + "WHERE target_seed.user_id IS NULL");
            long incomingFromGeneral = count(
                    "SELECT COUNT(*) FROM transfers t "
                            + "JOIN step16_reset_account_ids target_seed_account ON target_seed_account.account_id = t.to_account_id "
                            + "JOIN accounts source_account ON source_account.account_id = t.from_account_id "
                            + "LEFT JOIN step16_reset_user_ids source_seed ON source_seed.user_id = source_account.user_id "
                            + "WHERE source_seed.user_id IS NULL");
            if (outgoingToGeneral > 0 || incomingFromGeneral > 0) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "일반 계좌와 연결된 이체가 있어 reset을 중단했습니다: outgoing=%d, incoming=%d",
                        outgoingToGeneral, incomingFromGeneral));
            }
        }

        /** Seeder 이체와 일반 계좌 원장이 섞여 있으면 일반 원장 보호를 위해 중단한다. */
        private void ensureNoGeneralLedgerIsIncluded() throws SQLException {
            long generalAccountLedger = count(
                    "SELECT COUNT(*) FROM ledger_entries le "
                            + "JOIN step16_reset_transfer_ids seed_transfer ON seed_transfer.transfer_id = le.transfer_id "
                            + "LEFT JOIN step16_reset_account_ids seed_account ON seed_account.account_id = le.account_id "
                            + "WHERE seed_account.account_id IS NULL");
            long generalTransferLedger = count(
                    "SELECT COUNT(*) FROM ledger_entries le "
                            + "JOIN step16_reset_account_ids seed_account ON seed_account.account_id = le.account_id "
                            + "LEFT JOIN step16_reset_transfer_ids seed_transfer ON seed_transfer.transfer_id = le.transfer_id "
                            + "WHERE le.transfer_id IS NOT NULL AND seed_transfer.transfer_id IS NULL");
            if (generalAccountLedger > 0 || generalTransferLedger > 0) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "일반 데이터와 연결된 원장이 있어 reset을 중단했습니다: account=%d, transfer=%d",
                        generalAccountLedger, generalTransferLedger));
            }
        }

        /** Seeder 이체가 일반 사용자의 멱등성 기록과 연결되면 삭제 전에 중단한다. */
        private void ensureNoGeneralIdempotencyIsIncluded() throws SQLException {
            long generalIdempotencies = count(
                    "SELECT COUNT(*) FROM transfer_idempotencies ti "
                            + "JOIN step16_reset_transfer_ids seed_transfer ON seed_transfer.transfer_id = ti.transfer_id "
                            + "LEFT JOIN step16_reset_user_ids seed_user ON seed_user.user_id = ti.user_id "
                            + "WHERE seed_user.user_id IS NULL");
            if (generalIdempotencies > 0) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "일반 사용자와 연결된 멱등성 기록이 있어 reset을 중단했습니다: count=%d",
                        generalIdempotencies));
            }
        }

        /** DELETE를 2,000건씩 commit하여 대량 데이터를 하나의 트랜잭션으로 묶지 않는다. */
        private long deleteInChunks(String sql) throws SQLException {
            long total = 0L;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                while (true) {
                    statement.setInt(1, DELETE_BATCH_SIZE);
                    int deleted = statement.executeUpdate();
                    connection.commit();
                    total += deleted;
                    if (deleted < DELETE_BATCH_SIZE) {
                        return total;
                    }
                }
            }
        }

        /** PK 집합 준비에 필요한 DDL을 실행한다. */
        private void execute(String sql) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        /** PK 집합 준비에 필요한 INSERT를 실행한다. */
        private void executeUpdate(String sql) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }

        /** 안전성 검증용 COUNT 결과를 조회한다. */
        private long count(String sql) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }

        /** 현재 삭제 청크의 미커밋 변경만 되돌린다. */
        private void rollbackQuietly(Throwable cause) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                cause.addSuppressed(rollbackException);
            }
        }
    }
}
