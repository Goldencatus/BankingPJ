package com.bankingpj.backend.account;

import com.bankingpj.backend.account.domain.*;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.account.service.AccountService;
import com.bankingpj.backend.auth.token.AccessTokenIssuer;
import com.bankingpj.backend.ledger.repository.LedgerEntryRepository;
import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import com.bankingpj.backend.transfer.service.IdempotentTransferService;
import com.bankingpj.backend.user.domain.*;
import com.bankingpj.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(MySqlTestContainerConfiguration.class)
class AccountTransactionHistoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired AccountService service;
    @Autowired IdempotentTransferService transfers;
    @Autowired LedgerEntryRepository ledger;
    @Autowired AccessTokenIssuer tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;

    // 실제 입출금과 양방향 이체 원장이 정확한 금액과 이체 식별자로 함께 반환되는지 검증한다.
    @Test
    void returnsAllFinancialEventsWithoutSensitiveFields() throws Exception {
        User owner = user();
        Account a = account(owner);
        Account b = account(user());
        service.deposit(owner.getUserId(), a.getAccountId(), new BigDecimal("100.1234"));
        service.withdraw(owner.getUserId(), a.getAccountId(), new BigDecimal("10.0001"));
        transfers.transfer(owner.getUserId(), UUID.randomUUID().toString(), a.getAccountId(), b.getAccountNumber(), new BigDecimal("20.0000"));
        transfers.transfer(b.getUser().getUserId(), UUID.randomUUID().toString(), b.getAccountId(), a.getAccountNumber(), new BigDecimal("5.0000"));
        JsonNode data = history(owner, a.getAccountId(), "");
        var expected = ledger.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(a.getAccountId());
        assertThat(data.path("content").size()).isEqualTo(4);
        for (int i=0; i<4; i++) {
            var row = data.path("content").get(i);
            var entry = expected.get(3-i);
            assertThat(row.size()).isEqualTo(6);
            assertThat(row.path("ledgerEntryId").asLong()).isEqualTo(entry.getLedgerEntryId());
            assertThat(row.path("amount").decimalValue()).isEqualByComparingTo(entry.getAmount());
            assertThat(row.path("balanceAfter").decimalValue()).isEqualByComparingTo(entry.getBalanceAfter());
            assertThat(row.path("type").asString()).isEqualTo(entry.getType().name());
            assertThat(row.path("createdAt").asString()).isNotBlank();
            assertThat(row.path("transferId").isNull()).isEqualTo(entry.getTransfer()==null);
            if(entry.getTransfer()!=null) assertThat(row.path("transferId").asLong()).isEqualTo(entry.getTransfer().getTransferId());
        }
        assertThat(data.toString()).doesNotContain("accountNumber", "email", "password", "token", "userId");
        assertThat(service.findOne(owner.getUserId(), a.getAccountId()).balance()).isEqualByComparingTo("1075.1233");
    }

    // 같은 시각의 25개 원장을 ID 역순으로 페이지 분할하고 클라이언트 정렬을 무시하는지 검증한다.
    @Test
    void paginatesWithDeterministicOrderAndIndex() throws Exception {
        User owner=user(); Account a=account(owner);
        for(int i=0;i<25;i++) service.deposit(owner.getUserId(),a.getAccountId(),BigDecimal.ONE);
        jdbc.update("update ledger_entries set created_at='2026-01-01 00:00:00' where account_id=?",a.getAccountId());
        var entries=ledger.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(a.getAccountId());
        for(int page=0;page<3;page++) {
            JsonNode data=history(owner,a.getAccountId(),"?page="+page+"&size=10&sort=ledgerEntryId,asc");
            int count=page==2?5:10;
            assertThat(data.path("content").size()).isEqualTo(count);
            assertThat(data.path("page").asInt()).isEqualTo(page);
            assertThat(data.path("size").asInt()).isEqualTo(10);
            assertThat(data.path("totalElements").asLong()).isEqualTo(25);
            assertThat(data.path("totalPages").asInt()).isEqualTo(3);
            assertThat(data.path("first").asBoolean()).isEqualTo(page==0);
            assertThat(data.path("last").asBoolean()).isEqualTo(page==2);
            for(int i=0;i<count;i++) assertThat(data.path("content").get(i).path("ledgerEntryId").asLong()).isEqualTo(entries.get(24-page*10-i).getLedgerEntryId());
        }
        assertThat(jdbc.queryForList("select column_name from information_schema.statistics where table_schema=database() and table_name='ledger_entries' and index_name='idx_ledger_account_history' order by seq_in_index",String.class)).containsExactly("account_id","created_at","ledger_entry_id");
    }

    // 원장이 없는 모든 상태의 본인 계좌도 기본 페이지로 정상 조회되는지 검증한다.
    @ParameterizedTest @EnumSource(AccountStatus.class)
    void permitsEveryOwnedAccountStatus(AccountStatus state) throws Exception {
        User owner=user(); Account a=account(owner);
        jdbc.update("update accounts set status=? where account_id=?",state.name(),a.getAccountId());
        JsonNode data=history(owner,a.getAccountId(),"");
        assertThat(data.path("content").size()).isZero();
        assertThat(data.path("totalElements").asLong()).isZero();
        assertThat(data.path("page").asInt()).isZero();
        assertThat(data.path("size").asInt()).isEqualTo(20);
    }

    // 타인 계좌와 없는 계좌를 같은 오류로 숨기고 인증 누락도 차단하는지 검증한다.
    @Test
    void enforcesOwnershipAndAuthentication() throws Exception {
        User owner=user(); Account a=account(user());
        for(long id:new long[]{a.getAccountId(),Long.MAX_VALUE}) mvc.perform(get("/api/accounts/"+id+"/transactions").header("Authorization",bearer(owner))).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ACCOUNT_001")).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/accounts/"+a.getAccountId()+"/transactions")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_003"));
    }

    // 음수 페이지와 허용 범위 밖 크기를 공통 검증 오류로 거부하는지 검증한다.
    @ParameterizedTest @ValueSource(strings={"?page=-1","?size=0","?size=-1","?size=101"})
    void validatesPagination(String query) throws Exception {
        User owner=user(); Account a=account(owner);
        mvc.perform(get("/api/accounts/"+a.getAccountId()+"/transactions"+query).header("Authorization",bearer(owner))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    // 이체 원장 페이지 크기가 커져도 SQL 수가 고정되어 연관 이체 N+1이 없는지 검증한다.
    @Test
    void queryCountDoesNotGrowWithPageSize() {
        User owner=user(); Account a=account(owner); Account b=account(user());
        for(int i=0;i<12;i++) transfers.transfer(owner.getUserId(),UUID.randomUUID().toString(),a.getAccountId(),b.getAccountNumber(),BigDecimal.ONE);
        var stats=emf.unwrap(SessionFactory.class).getStatistics();
        boolean previous=stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear(); service.transactions(owner.getUserId(),a.getAccountId(),0,2);
            long small=stats.getPrepareStatementCount();
            stats.clear(); service.transactions(owner.getUserId(),a.getAccountId(),0,10);
            assertThat(stats.getPrepareStatementCount()).isEqualTo(small).isEqualTo(4);
            assertThat(stats.getEntityFetchCount()).isZero();
        } finally { stats.setStatisticsEnabled(previous); }
    }

    // 테스트용 고유 회원을 저장한다.
    private User user() { return users.saveAndFlush(new User(UUID.randomUUID()+"@example.com","test-hash","History",UserStatus.ACTIVE)); }
    // 테스트용 계좌를 초기 잔액과 함께 저장한다.
    private Account account(User owner) { return accounts.saveAndFlush(new Account(owner,UUID.randomUUID().toString().replace("-","").substring(0,30),new BigDecimal("1000.0000"),AccountStatus.ACTIVE)); }
    // 실제 JWT 발급기로 인증 헤더를 구성한다.
    private String bearer(User owner) { return "Bearer "+tokens.issue(owner,Instant.now()); }
    // MVC 응답에서 공통 응답의 거래내역 데이터를 읽는다.
    private JsonNode history(User owner,long id,String query) throws Exception { return mapper.readTree(mvc.perform(get("/api/accounts/"+id+"/transactions"+query).header("Authorization",bearer(owner))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data"); }
}
