package com.sw.remittanceservice.account.usecase;


import com.sw.remittanceservice.account.entity.Account;
import com.sw.remittanceservice.account.entity.AccountLimitSetting;
import com.sw.remittanceservice.account.entity.enums.AccountStatus;
import com.sw.remittanceservice.account.repository.AccountLimitSettingRepository;
import com.sw.remittanceservice.account.repository.AccountRepository;
import com.sw.remittanceservice.account.repository.TransactionRepository;
import com.sw.remittanceservice.account.usecase.policy.FeeCalculatorFinder;
import com.sw.remittanceservice.account.usecase.policy.dto.FeeRequest;
import com.sw.remittanceservice.account.usecase.policy.dto.FeeResponse;
import com.sw.remittanceservice.account.usecase.policy.dto.enums.FeePolicyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

@SpringBootTest
public class TransferUseCaseConcurrencyTest {

    @Autowired
    private TransferUseCase transferUseCase;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountLimitSettingRepository accountLimitSettingRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @MockitoBean
    private FeeCalculatorFinder feeCalculatorFinder;

    private String accountNoA;
    private String accountNoB;
    private Long accountIdA;
    private Long accountIdB;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();

        // 계좌 A 생성 (100만원)
        Account accountA = accountRepository.save(new Account(null, UUID.randomUUID().toString(), 1_000_000L, AccountStatus.ACTIVE, now, now));
        accountLimitSettingRepository.save(AccountLimitSetting.defaultOf(accountA.getAccountId()));

        // 계좌 B 생성 (100만원)
        Account accountB = accountRepository.save(new Account(null, UUID.randomUUID().toString(), 1_000_000L, AccountStatus.ACTIVE, now, now));
        accountLimitSettingRepository.save(AccountLimitSetting.defaultOf(accountB.getAccountId()));

        accountNoA = accountA.getAccountNo();
        accountNoB = accountB.getAccountNo();
        accountIdA = accountA.getAccountId();
        accountIdB = accountB.getAccountId();

        given(feeCalculatorFinder.calculate(any(FeeRequest.class)))
                .willReturn(new FeeResponse(FeePolicyType.DEFAULT, 0.0, 0L, now)); // 테스트 편의상 수수료 0원
    }


    @Test
    @DisplayName("동시성: A에서 B로 100명이 동시에 1만원씩 송금하면 A는 0원, B는 200만원이 된다.")
    void concurrent_transfer_test() throws Exception {
        int threadCount = 100;
        long transferAmount = 10_000L;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    transferUseCase.execute(accountNoA, accountNoB, transferAmount, UUID.randomUUID().toString());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Transfer failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // 결과 검증
        Account finalA = accountRepository.findById(accountIdA).orElseThrow();
        Account finalB = accountRepository.findById(accountIdB).orElseThrow();

        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(finalA.getBalance()).isEqualTo(0L);
        assertThat(finalB.getBalance()).isEqualTo(2_000_000L);

        // 트랜잭션 기록은 출금/입금 각 100건이어야 함
        assertThat(transactionRepository.countByAccountId(accountIdA)).isEqualTo(threadCount);
        assertThat(transactionRepository.countByAccountId(accountIdB)).isEqualTo(threadCount);
    }


    @Test
    @DisplayName("A→B와 B→A 송금이 동시에 100건 발생해도 데드락 없이 정합성이 유지된다.")
    void deadlock_prevention_test() throws Exception {
        // Given
        int threadCount = 100; // 총 100번의 송금 (A->B 50번, B->A 50번)
        long transferAmount = 10_000L;

        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // When
        for (int i = 0; i < threadCount; i++) {
            boolean isAtoB = (i % 2 == 0); // 절반은 A->B, 절반은 B->A
            String fromNo = isAtoB ? accountNoA : accountNoB;
            String toNo = isAtoB ? accountNoB : accountNoA;

            executorService.submit(() -> {
                try {
                    transferUseCase.execute(fromNo, toNo, transferAmount, UUID.randomUUID().toString());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.err.println("Transfer failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // Then
        Account finalA = accountRepository.findById(accountIdA).orElseThrow();
        Account finalB = accountRepository.findById(accountIdB).orElseThrow();

        // 1. 모든 요청이 성공했는지 확인 (데드락 발생 시 일부 타임아웃/실패 발생)
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(failCount.get()).isEqualTo(0);

        // 2. 최종 잔액 검증
        // A: 100만 - (50번 * 1만) + (50번 * 1만) = 100만
        // B: 100만 - (50번 * 1만) + (50번 * 1만) = 100만
        assertThat(finalA.getBalance()).isEqualTo(1_000_000L);
        assertThat(finalB.getBalance()).isEqualTo(1_000_000L);

        // 트랜잭션 기록은 출금/입금 각 100건이어야 함
        assertThat(transactionRepository.countByAccountId(accountIdA)).isEqualTo(threadCount);
        assertThat(transactionRepository.countByAccountId(accountIdB)).isEqualTo(threadCount);
    }
}
