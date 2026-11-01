package com.yottaflex.budget.validation;

import com.yottaflex.budget.TestData;
import com.yottaflex.budget.generator.GeneratorConfig;
import com.yottaflex.budget.generator.SyntheticDataGenerator;
import com.yottaflex.budget.model.Transaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetValidatorTest {

    @Test
    void generatedDatasetPassesAllChecks() {
        var r = TestData.tiny();
        var checks = new DatasetValidator().validate(r.dataset(), r.truth(), 20, 10_000);
        assertThat(checks).allSatisfy(c -> assertThat(c.status()).as(c.name()).isNotEqualTo("FAIL"));
    }

    @Test
    void detectsBrokenForeignKeyAndLedgerBreak() {
        var r = new SyntheticDataGenerator(GeneratorConfig.forScale("tiny", GeneratorConfig.DEFAULT_SEED)).generate();
        Transaction t = r.dataset().transactions.get(10);
        r.dataset().transactions.set(10, new Transaction(t.transactionId(), "ACC-USR-999999-01", t.merchantId(),
                t.postedAtUtc(), t.amountCents(), t.currency(), t.descriptor(), t.txnType(), t.mcc(), t.balanceAfterCents()));
        Transaction u = r.dataset().transactions.get(20);
        r.dataset().transactions.set(20, new Transaction(u.transactionId(), u.accountId(), u.merchantId(),
                u.postedAtUtc(), u.amountCents() + 1, u.currency(), u.descriptor(), u.txnType(), u.mcc(), u.balanceAfterCents()));
        var checks = new DatasetValidator().validate(r.dataset(), r.truth(), null, null);
        assertThat(checks).anyMatch(c -> c.name().equals("fk.transaction.account_id") && c.status().equals("FAIL"));
        assertThat(checks).anyMatch(c -> c.name().equals("reconcile.running_balance_chain") && c.status().equals("FAIL"));
    }
}
