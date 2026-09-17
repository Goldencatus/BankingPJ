package com.bankingpj.backend.performance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerformanceDataSeederTest {

    /** 세 가지 규모가 요구된 테이블별 건수로 변환되는지 검증한다. */
    @Test
    void createsExpectedPlanForEachScale() {
        assertThat(PerformanceDataSeeder.planFor("SMALL")).isEqualTo(
                new PerformanceDataSeeder.SeedPlan("SMALL", 1_000, 2_000, 10_000, 20_000));
        assertThat(PerformanceDataSeeder.planFor("medium")).isEqualTo(
                new PerformanceDataSeeder.SeedPlan("MEDIUM", 10_000, 20_000, 100_000, 200_000));
        assertThat(PerformanceDataSeeder.planFor(" LARGE ")).isEqualTo(
                new PerformanceDataSeeder.SeedPlan("LARGE", 100_000, 200_000, 1_000_000, 2_000_000));
    }

    /** 지원하지 않는 규모를 입력하면 DB 접속 전에 실패하는지 검증한다. */
    @Test
    void rejectsUnknownScale() {
        assertThatThrownBy(() -> PerformanceDataSeeder.planFor("huge"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SMALL");
    }

    /** reset이 전용 성능 테스트 DB 이름만 허용하는지 검증한다. */
    @Test
    void resetAllowsOnlyPerformanceDatabase() {
        PerformanceDataResetter.validateDatabaseName("bankingpj_perf");

        assertThatThrownBy(() -> PerformanceDataResetter.validateDatabaseName("bankingpj"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bankingpj_perf");
    }
}
