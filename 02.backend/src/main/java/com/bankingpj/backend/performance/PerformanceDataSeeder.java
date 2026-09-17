package com.bankingpj.backend.performance;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 성능 테스트용 데이터만 명시적인 Gradle 작업으로 생성하는 JDBC Seeder. */
public final class PerformanceDataSeeder {

    private static final String TEST_EMAIL_DOMAIN = "@bankingpj.test";
    private static final long FIRST_ACCOUNT_NUMBER = 97_000_000_000_001L;
    private static final int BATCH_SIZE = 2_000;
    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final BigDecimal ZERO = new BigDecimal("0.0000");
    private static final BigDecimal DEFAULT_BALANCE = new BigDecimal("1000000000.0000");
    private static final BigDecimal HOT_BALANCE = new BigDecimal("999999999999.0000");
    private static final long TIMELINE_SECONDS = Duration.ofDays(365).getSeconds();

    /** Seeder를 인스턴스화하지 못하게 하고 명시적인 main 진입점만 제공한다. */
    private PerformanceDataSeeder() {
    }

    /** 명시된 환경변수에서 DB 접속 정보를 읽고 Seeder를 실행한다. */
    public static void main(String[] args) {
        try {
            SeedPlan plan = planFor(args.length == 0 ? "" : args[0]);
            Map<String, String> environment = System.getenv();
            String databaseName = requiredEnvironment(environment, "DB_NAME");
            String username = requiredEnvironment(environment, "DB_USERNAME");
            String databasePassword = requiredEnvironment(environment, "DB_PASSWORD");
            String testPassword = requiredEnvironment(environment, "SEEDER_TEST_PASSWORD");
            validateTestPassword(testPassword);

            String jdbcUrl = "jdbc:mysql://localhost:3306/" + databaseName
                    + "?serverTimezone=UTC&characterEncoding=UTF-8&rewriteBatchedStatements=true";
            System.out.printf(Locale.ROOT,
                    "[SEEDER] database=%s scale=%s users=%d accounts=%d transfers=%d ledger_entries=%d%n",
                    databaseName, plan.scale(), plan.users(), plan.accounts(), plan.transfers(), plan.ledgerEntries());

            long startedAt = System.nanoTime();
            SeedResult result;
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, databasePassword)) {
                connection.setAutoCommit(false);
                result = new SeedRunner(connection, plan).seed(testPassword);
            }
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            System.out.printf(Locale.ROOT,
                    "[SEEDER] completed users=%d accounts=%d transfers=%d ledger_entries=%d elapsedMs=%d%n",
                    result.users(), result.accounts(), result.transfers(), result.ledgerEntries(), elapsedMillis);
        } catch (IllegalArgumentException exception) {
            System.err.println("[SEEDER] " + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            // 오류 원인만 출력하여 접속 비밀번호나 테스트 비밀번호가 로그에 남지 않게 한다.
            System.err.println("[SEEDER] failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    /** SMALL/MEDIUM/LARGE 이름을 검증하고 생성 건수 계획으로 변환한다. */
    static SeedPlan planFor(String rawScale) {
        SeedScale scale = SeedScale.parse(rawScale);
        return new SeedPlan(scale.name(), scale.users, scale.accounts, scale.transfers, scale.ledgerEntries);
    }

    /** 환경변수의 존재만 확인하고 값은 호출부에서만 사용하도록 반환한다. */
    private static String requiredEnvironment(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("필수 환경변수가 없습니다: " + name);
        }
        return value;
    }

    /** 로컬 성능 테스트 비밀번호가 Backend의 기본 길이와 BCrypt 제한을 지키는지 검증한다. */
    private static void validateTestPassword(String password) {
        int byteLength = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < PASSWORD_MIN_LENGTH || byteLength > 72) {
            throw new IllegalArgumentException("SEEDER_TEST_PASSWORD는 8자 이상, UTF-8 72바이트 이하여야 합니다");
        }
    }

    /** 계정 번호를 고정된 성능 테스트 검색 prefix로 생성한다. */
    private static String accountNumber(long accountIndex) {
        return String.format(Locale.ROOT, "%014d", FIRST_ACCOUNT_NUMBER + accountIndex);
    }

    /** 전체 기간에 생성 시각을 고르게 분산한다. */
    private static LocalDateTime timelineTime(LocalDateTime start, int index, int total) {
        if (total <= 1) {
            return start;
        }
        long offsetSeconds = (TIMELINE_SECONDS * (long) index) / (total - 1L);
        long extraNanos = (index % 1_000L) * 1_000_000L;
        return start.plusSeconds(offsetSeconds).plusNanos(extraNanos);
    }

    enum SeedScale {
        SMALL(1_000, 2_000, 10_000, 20_000),
        MEDIUM(10_000, 20_000, 100_000, 200_000),
        LARGE(100_000, 200_000, 1_000_000, 2_000_000);

        private final int users;
        private final int accounts;
        private final int transfers;
        private final int ledgerEntries;

        /** 스케일별 대상 테이블 행 수를 보관한다. */
        SeedScale(int users, int accounts, int transfers, int ledgerEntries) {
            this.users = users;
            this.accounts = accounts;
            this.transfers = transfers;
            this.ledgerEntries = ledgerEntries;
        }

        /** 대소문자를 무시하고 스케일 이름을 찾는다. */
        static SeedScale parse(String rawScale) {
            try {
                return valueOf(rawScale.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("seedScale은 SMALL, MEDIUM, LARGE 중 하나여야 합니다");
            }
        }
    }

    record SeedPlan(String scale, int users, int accounts, int transfers, int ledgerEntries) {
    }

    record SeedResult(long users, long accounts, long transfers, long ledgerEntries) {
    }

    private static final class SeedRunner {
        private final Connection connection;
        private final SeedPlan plan;
        private final LocalDateTime timelineStart = LocalDateTime.now(ZoneOffset.UTC).minusDays(365).minusSeconds(30);
        private final long[] accountIds;
        private final BigDecimal[] accountBalances;
        private final boolean[] activeAccounts;
        private final long userIdStart;
        private final long accountIdStart;
        private final long transferIdStart;
        private final long ledgerEntryIdStart;

        /** 연결과 생성 계획을 준비하고 대상 DB의 중복 marker를 확인한다. */
        private SeedRunner(Connection connection, SeedPlan plan) throws SQLException {
            this.connection = connection;
            this.plan = plan;
            ensureTargetIsUnused();
            this.userIdStart = nextId("users", "user_id");
            this.accountIdStart = nextId("accounts", "account_id");
            this.transferIdStart = nextId("transfers", "transfer_id");
            this.ledgerEntryIdStart = nextId("ledger_entries", "ledger_entry_id");
            this.accountIds = new long[plan.accounts()];
            this.accountBalances = new BigDecimal[plan.accounts()];
            this.activeAccounts = new boolean[plan.accounts()];
        }

        /** 테이블별로 배치 삽입하고 생성된 행 수를 확인한다. */
        private SeedResult seed(String testPassword) throws SQLException {
            try {
                insertUsers(testPassword);
                insertAccounts();
                List<Integer> activePool = activeAccountPool();
                insertTransfersAndLedgers(activePool);
                updateAccountBalances();
                return verifyCounts();
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(exception);
                throw exception;
            }
        }

        /** 현재 청크의 미커밋 변경만 되돌려 연결 종료 시 남는 부분 트랜잭션을 없앤다. */
        private void rollbackQuietly(Throwable cause) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                cause.addSuppressed(rollbackException);
            }
        }

        /** Seeder marker가 이미 존재하면 중복 생성하지 않고 즉시 중단한다. */
        private void ensureTargetIsUnused() throws SQLException {
            if (count("SELECT COUNT(*) FROM users WHERE email LIKE 'step16-%@bankingpj.test' OR email LIKE 'k6-%@bankingpj.test'") > 0
                    || count("SELECT COUNT(*) FROM accounts WHERE account_number LIKE '970000000000%'") > 0) {
                throw new IllegalStateException("STEP 16 Seeder 데이터가 이미 존재합니다. 대상 DB를 확인하세요");
            }
        }

        /** 기존 최대 PK 다음 번호부터 명시적으로 사용할 시작 ID를 계산한다. */
        private long nextId(String table, String column) throws SQLException {
            String sql = "SELECT COALESCE(MAX(" + column + "), 0) + 1 FROM " + table;
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }

        /** users를 BCrypt 해시와 함께 일정한 크기의 배치로 삽입한다. */
        private void insertUsers(String testPassword) throws SQLException {
            String sql = "INSERT INTO users (user_id, email, password_hash, name, status, role, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, 'ACTIVE', 'USER', ?, ?)";
            String passwordHash = new BCryptPasswordEncoder().encode(testPassword);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int offset = 0; offset < plan.users(); offset += BATCH_SIZE) {
                    int end = Math.min(offset + BATCH_SIZE, plan.users());
                    for (int i = offset; i < end; i++) {
                        LocalDateTime createdAt = timelineTime(timelineStart, i, plan.users());
                        statement.setLong(1, userIdStart + i);
                        statement.setString(2, userEmail(i));
                        statement.setString(3, passwordHash);
                        statement.setString(4, userName(i));
                        statement.setObject(5, createdAt);
                        statement.setObject(6, createdAt);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                    connection.commit();
                }
            }
        }

        /** users의 고정 k6 사용자와 일반 성능 테스트 사용자를 구분한다. */
        private static String userEmail(int index) {
            return switch (index) {
                case 0 -> "k6-reader" + TEST_EMAIL_DOMAIN;
                case 1 -> "k6-ledger" + TEST_EMAIL_DOMAIN;
                case 2 -> "k6-hot" + TEST_EMAIL_DOMAIN;
                default -> String.format(Locale.ROOT, "step16-user-%06d%s", index, TEST_EMAIL_DOMAIN);
            };
        }

        /** 고정 사용자가 k6 결과에서 쉽게 구분되도록 이름을 만든다. */
        private static String userName(int index) {
            return switch (index) {
                case 0 -> "k6-reader";
                case 1 -> "k6-ledger-heavy";
                case 2 -> "k6-hot";
                default -> String.format(Locale.ROOT, "step16-user-%06d", index);
            };
        }

        /** accounts를 유일한 계정 번호와 상태별 잔액으로 배치 삽입한다. */
        private void insertAccounts() throws SQLException {
            String sql = "INSERT INTO accounts (account_id, user_id, account_number, balance, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int offset = 0; offset < plan.accounts(); offset += BATCH_SIZE) {
                    int end = Math.min(offset + BATCH_SIZE, plan.accounts());
                    for (int i = offset; i < end; i++) {
                        String status = accountStatus(i);
                        BigDecimal balance = initialBalance(i, status);
                        LocalDateTime createdAt = timelineTime(timelineStart, i, plan.accounts());
                        accountIds[i] = accountIdStart + i;
                        accountBalances[i] = balance;
                        activeAccounts[i] = "ACTIVE".equals(status);
                        statement.setLong(1, accountIds[i]);
                        statement.setLong(2, userIdStart + (i / 2));
                        statement.setString(3, accountNumber(i));
                        statement.setBigDecimal(4, balance);
                        statement.setString(5, status);
                        statement.setObject(6, createdAt);
                        statement.setObject(7, createdAt);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                    connection.commit();
                }
            }
        }

        /** 일부 계좌를 정지/폐쇄하여 상태 필터 성능을 확인할 수 있게 한다. */
        private static String accountStatus(int index) {
            if (index % 200 == 11) {
                return "CLOSED";
            }
            if (index % 200 == 10) {
                return "SUSPENDED";
            }
            return "ACTIVE";
        }

        /** 폐쇄 계좌는 0원, hot 계좌는 충분한 잔액으로 초기화한다. */
        private static BigDecimal initialBalance(int index, String status) {
            if ("CLOSED".equals(status)) {
                return ZERO;
            }
            if (index == 4) {
                return HOT_BALANCE;
            }
            return DEFAULT_BALANCE;
        }

        /** 이체에 사용할 ACTIVE 계좌만 골라 FK와 상태 조건을 만족시킨다. */
        private List<Integer> activeAccountPool() {
            List<Integer> pool = new ArrayList<>();
            for (int i = 0; i < activeAccounts.length; i++) {
                if (activeAccounts[i]) {
                    pool.add(i);
                }
            }
            if (pool.size() < 2) {
                throw new IllegalStateException("이체를 만들 ACTIVE 계좌가 두 개 미만입니다");
            }
            return pool;
        }

        /** 이체와 두 개의 원장을 같은 청크 트랜잭션으로 삽입한다. */
        private void insertTransfersAndLedgers(List<Integer> activePool) throws SQLException {
            String transferSql = "INSERT INTO transfers (transfer_id, from_account_id, to_account_id, amount, status, created_at, completed_at) "
                    + "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?)";
            String ledgerSql = "INSERT INTO ledger_entries (ledger_entry_id, account_id, transfer_id, type, amount, balance_after, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            int heavyAccountIndex = 2;
            for (int offset = 0; offset < plan.transfers(); offset += BATCH_SIZE) {
                int end = Math.min(offset + BATCH_SIZE, plan.transfers());
                try (PreparedStatement transferStatement = connection.prepareStatement(transferSql);
                     PreparedStatement ledgerStatement = connection.prepareStatement(ledgerSql)) {
                    for (int i = offset; i < end; i++) {
                        int partnerPoolIndex = i % (activePool.size() - 1);
                        int partner = activePool.get(partnerPoolIndex);
                        if (partner == heavyAccountIndex) {
                            partner = activePool.get(activePool.size() - 1);
                        }
                        int fromIndex = i % 2 == 0 ? heavyAccountIndex : partner;
                        int toIndex = i % 2 == 0 ? partner : heavyAccountIndex;
                        BigDecimal amount = BigDecimal.valueOf((i % 100L + 1L) * 1_000L).setScale(4);
                        BigDecimal fromBalanceAfter = accountBalances[fromIndex].subtract(amount).setScale(4);
                        BigDecimal toBalanceAfter = accountBalances[toIndex].add(amount).setScale(4);
                        accountBalances[fromIndex] = fromBalanceAfter;
                        accountBalances[toIndex] = toBalanceAfter;
                        LocalDateTime createdAt = timelineTime(timelineStart, i, plan.transfers());
                        long transferId = transferIdStart + i;
                        long ledgerId = ledgerEntryIdStart + (long) i * 2L;

                        transferStatement.setLong(1, transferId);
                        transferStatement.setLong(2, accountIds[fromIndex]);
                        transferStatement.setLong(3, accountIds[toIndex]);
                        transferStatement.setBigDecimal(4, amount);
                        transferStatement.setObject(5, createdAt);
                        transferStatement.setObject(6, createdAt.plusSeconds(30));
                        transferStatement.addBatch();

                        addLedgerBatch(ledgerStatement, ledgerId, accountIds[fromIndex], transferId,
                                "DEBIT", amount.negate().setScale(4), fromBalanceAfter, createdAt);
                        addLedgerBatch(ledgerStatement, ledgerId + 1L, accountIds[toIndex], transferId,
                                "CREDIT", amount, toBalanceAfter, createdAt);
                    }
                    transferStatement.executeBatch();
                    ledgerStatement.executeBatch();
                    connection.commit();
                }
            }
        }

        /** 원장 한 건의 금액 부호와 잔액을 JDBC 배치에 추가한다. */
        private static void addLedgerBatch(PreparedStatement statement, long ledgerId, long accountId,
                                           long transferId, String type, BigDecimal amount,
                                           BigDecimal balanceAfter, LocalDateTime createdAt) throws SQLException {
            statement.setLong(1, ledgerId);
            statement.setLong(2, accountId);
            statement.setLong(3, transferId);
            statement.setString(4, type);
            statement.setBigDecimal(5, amount);
            statement.setBigDecimal(6, balanceAfter);
            statement.setObject(7, createdAt);
            statement.addBatch();
        }

        /** 계산한 최종 잔액을 계좌 테이블에 청크별로 반영한다. */
        private void updateAccountBalances() throws SQLException {
            String sql = "UPDATE accounts SET balance = ?, updated_at = ? WHERE account_id = ?";
            LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int offset = 0; offset < accountBalances.length; offset += BATCH_SIZE) {
                    int end = Math.min(offset + BATCH_SIZE, accountBalances.length);
                    for (int i = offset; i < end; i++) {
                        statement.setBigDecimal(1, accountBalances[i]);
                        statement.setObject(2, updatedAt);
                        statement.setLong(3, accountIds[i]);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                    connection.commit();
                }
            }
        }

        /** marker와 INSERT 전에 확보한 ID 범위로 생성 건수를 확인한다. */
        private SeedResult verifyCounts() throws SQLException {
            long users = count("SELECT COUNT(*) FROM users WHERE email LIKE 'step16-%@bankingpj.test' OR email LIKE 'k6-%@bankingpj.test'");
            long accounts = countById("accounts", "account_id", accountIdStart, plan.accounts());
            long transfers = countById("transfers", "transfer_id", transferIdStart, plan.transfers());
            long ledgerEntries = countById("ledger_entries", "ledger_entry_id", ledgerEntryIdStart, plan.ledgerEntries());
            if (users != plan.users() || accounts != plan.accounts()
                    || transfers != plan.transfers() || ledgerEntries != plan.ledgerEntries()) {
                long userIdEndExclusive = idEndExclusive(userIdStart, plan.users());
                long accountIdEndExclusive = idEndExclusive(accountIdStart, plan.accounts());
                long transferIdEndExclusive = idEndExclusive(transferIdStart, plan.transfers());
                long ledgerEntryIdEndExclusive = idEndExclusive(ledgerEntryIdStart, plan.ledgerEntries());
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "Seeder 생성 건수 검증 실패: "
                                + "expected{users=%d, accounts=%d, transfers=%d, ledgerEntries=%d}, "
                                + "actual{users=%d, accounts=%d, transfers=%d, ledgerEntries=%d}, "
                                + "idRanges{userId=[%d,%d), accountId=[%d,%d), "
                                + "transferId=[%d,%d), ledgerEntryId=[%d,%d)}",
                        plan.users(), plan.accounts(), plan.transfers(), plan.ledgerEntries(),
                        users, accounts, transfers, ledgerEntries,
                        userIdStart, userIdEndExclusive,
                        accountIdStart, accountIdEndExclusive,
                        transferIdStart, transferIdEndExclusive,
                        ledgerEntryIdStart, ledgerEntryIdEndExclusive));
            }
            return new SeedResult(users, accounts, transfers, ledgerEntries);
        }

        /** 시작 ID와 생성 건수로 검증 범위의 끝 값을 안전하게 계산한다. */
        private static long idEndExclusive(long start, int size) {
            return Math.addExact(start, (long) size);
        }

        /** 단일 COUNT 쿼리의 결과를 읽는다. */
        private long count(String sql) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }

        /** 명시적 PK 범위에 해당하는 생성 건수를 읽는다. */
        private long countById(String table, String column, long start, int size) throws SQLException {
            long endExclusive = idEndExclusive(start, size);
            String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + column + " >= ? AND " + column + " < ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, start);
                statement.setLong(2, endExclusive);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getLong(1);
                }
            }
        }
    }
}
