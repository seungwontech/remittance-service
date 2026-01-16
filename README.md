# 송금 서비스

계좌 등록/삭제, 입금/출금/이체, 거래내역 조회 기능을 제공하는 송금 서비스입니다.  
Spring Boot + JPA(Hibernate) 기반이며, 멱등성/동시성 이슈를 고려하여 Redis 락 및 DB 비관적 락을 적용했습니다.

## 기술 스택

- Java 17
- Spring Boot 3.5
- Spring Data JPA (Hibernate)
- MySQL 8
- Redis (분산 락을 통한 멱등성 제어용)
- Docker / Docker Compose
- JUnit5
- Gradle

---

## How to Run (Docker Compose)

### 1) 실행

```bash
docker compose up -d
```  

### 2) 종료

```bash
docker compose down
```

### 3) 볼륨까지 삭제(데이터 초기화)

```bash
docker compose down -v
```

### 기본 포트 예시

- API Server: http://localhost:8080
- MySQL: localhost:3306
- Redis: localhost:6379

---

## API Spec

### 1. 계좌 등록/조회/삭제

### 계좌 등록

```
POST /api/accounts
```

Request Body: 없음

Response

```
{
  "accountNo": "uuid",
  "balance": 0,
  "accountStatus": "ACTIVE",
  "dailyWithdrawLimit": 1000000,
  "dailyTransferLimit": 3000000
}
```

### 계좌 조회

```
GET /api/accounts/{accountNo}
```

### 계좌 삭제

```
DELETE /api/accounts/{accountNo}
```

- Response: 204 No Content
- `CLOSED` 상태값으로 논리적 삭제 (Soft Delete)
- 해지된 계좌(CLOSED)는 모든 입금, 출금, 이체 거래가 즉시 차단

### 2. 입금/출금/이체

### 입금

```
POST /api/accounts/{accountNo}/deposit
```

Request

```
{
  "amount": 10000,
  "transactionRequestId": "tx-uuid"
}
```

### 출금 (일 한도: 1,000,000원)

```
POST /api/accounts/{accountNo}/withdraw
```

Request

```
{
  "amount": 10000,
  "transactionRequestId": "tx-uuid"
}
```

정책

- 출금 일 한도: 1,000,000원

### 이체

```
POST /api/transfers
```

Request

```
{
  "fromAccountNo": "from-account-no",
  "toAccountNo": "to-account-no",
  "amount": 10000,
  "transactionRequestId": "tx-uuid"
}
```

정책

- 시간대별 차등 수수료:
    - 주간 (06:00 ~ 22:59): 기본 수수료 적용 (1%)
    - 야간 (23:00 ~ 05:59): 심야 할증 수수료 2% 적용
- 이체 일 한도: 3,000,000원
- 동일 계좌 이체 금지

### 거래내역 조회 (최신순)

```
GET /api/accounts/{accountNo}/transactions?page={page}&pageSize={pageSize}
```

정책

- 최신순 정렬(created_at desc)

- 페이징 기반 조회

---

## Database 설계

| Table                       | 설명                                                         |
|-----------------------------|------------------------------------------------------------|
| `account`                   | 계좌 기본 정보 및 잔액/상태 관리                                        |
| `account_limit_setting`     | 계좌별 일 출금/이체 한도 설정(기본값: 출금 1,000,000 / 이체 3,000,000)        |
| `account_daily_limit_usage` | 계좌별 “일자 단위” 출금/이체 누적 사용량(동시 갱신을 위해 락 사용)                   |
| `transaction`               | 거래 내역(입금/출금/이체 기록), 멱등성 키(transaction_request_id) 기반 중복 방지 |

### 1. account

- 계좌의 현재 잔액(balance)과 상태(account_status)를 저장합니다.
- 삭제는 물리 삭제가 아닌 Soft Delete로 처리하며 CLOSED 상태로 변경됩니다.
- 모든 금융 거래(입금/출금/이체) 시 계좌 상태를 검증하며, ACTIVE가 아닌 경우 오류를 반환합니다.
- 동시 입금/출금/이체 시 잔액 정합성을 위해 PESSIMISTIC_WRITE(FOR UPDATE)로 조회 후 갱신합니다.

컬럼 
- account_id (PK)
- account_no (계좌번호, UUID)
- balance (현재 잔액)
- account_status (ACTIVE / CLOSED)
- created_at, updated_at
### 2. account_limit_setting

- 계좌별 한도 정책을 저장합니다.
- 계좌 생성 시 기본 한도를 자동 생성합니다.
컬럼
- account_limit_setting_id (PK)
- account_id (계좌 식별자)
- daily_withdraw_limit (일 출금 한도)
- daily_transfer_limit (일 이체 한도)
- created_at, updated_at

### 3. account_daily_limit_usage

- 일자별(YYYY-MM-DD) 누적 출금/이체 사용량을 저장합니다.
- 출금/이체 요청 시 해당 일자 레코드를 조회하여 한도 초과 여부를 판단하고 누적 값을 갱신합니다.

컬럼
- account_daily_limit_usage_id (PK)
- account_id
- limit_date (한도 기준 일자(YYYY-MM-DD))
- withdraw_used (오늘 출금 누적)
- transfer_used (오늘 이체 누적)
- created_at, updated_at

```
uk_account_date (account_id, limit_date) 유니크 제약으로 하루에 계좌당 1개의 사용량 레코드만 존재하도록 보장합니다.
```

### 4. transaction

- 입금/출금/이체 내역을 저장합니다.
- 멱등성 처리를 위해 (account_id, transaction_request_id)에 유니크 제약이 있습니다.

컬럼
- transaction_id (PK)
- account_id
- transaction_request_id (요청 식별자, 멱등성 키)
- transaction_type (DEPOSIT / WITHDRAW)
- transaction_status (SUCCESS / PENDING)
- amount (거래 금액)
- balance_after_transaction (거래 후 잔액)
- target_account_no (이체 상대 계좌번호)
- 수수료 관련: fee_policy_type, fee, fee_rate, fee_applied_at
- created_at


```
uk_account_transaction_request (account_id, transaction_request_id)  
계좌 단위로 동일 transactionRequestId 중복 저장 방지
```

---

## 동시성 & 멱등성

### 1. 동시성(잔액/한도 갱신)

- DB 비관적 락(PESSIMISTIC_WRITE, FOR UPDATE)을 사용하여 동시 출금/입금/이체 시 잔액 갱신 충돌을 방지합니다.
- 출금/이체의 일 누적 한도 관리를 위해 AccountDailyLimitUsage를 사용하고, 해당 레코드도 락 조회로 동시 갱신 충돌을 방지합니다.
- 이체는 두 계좌에 락이 필요하므로 데드락을 줄이기 위해 계좌 ID 정렬(min/max) 순서로 락을 획득합니다.

### 2. 멱등성(transactionRequestId)

- 요청마다 transactionRequestId를 받습니다.
- Redis에 transaction-request-id-lock::{transactionRequestId} 키를 SETNX로 등록
- 동일 transactionRequestId가 재요청될 경우 중복 처리를 방지합니다.
- 락 획득에 실패한 경우, 기존 트랜잭션 내역을 조회하여 동일 요청에 대해 동일한 응답을 반환하도록 처리했습니다.

```
현재 TTL은 1초로 설정되어 있습니다. 운영 환경에서는 트랜잭션 처리 시간/재시도 정책에 맞춰 TTL 조정이 필요합니다.
```

---

## 오류 응답/예외 처리

### 공통 오류 응답 포맷

비즈니스 예외를 CoreException으로 통일하고, GlobalExceptionHandler에서 아래 공통 포맷으로 응답합니다.

Response

```
  "errorCode": BAD_REQUEST,
  "message": "에러 메시지",
  "payload": "추가 정보(옵션)"
```

주요 에러 케이스(예시)

- 계좌 없음: ACCOUNT_NOT_FOUND
- 동일 계좌 이체: SAME_ACCOUNT_TRANSFER
- 출금 일 한도 초과: EXCEED_DAILY_WITHDRAW_LIMIT
- 이체 일 한도 초과: EXCEED_DAILY_TRANSFER_LIMIT
- 수수료 계산기 없음: CALCULATOR_NOT_FOUND

응답 예시

```
{
  "errorCode": "NOT_FOUND",
  "message": "계좌을 찾을 수 없습니다.",
  "payload": "abc-account-no"
}
```

---

## 테스트

```bash
./gradlew test
```

- 단위 테스트 및 통합 테스트 포함
- 멀티 스레드 기반 동시성 테스트
- 멱등성(transactionRequestId) 중복 요청 검증 테스트

--- 
