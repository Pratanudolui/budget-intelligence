package com.yottaflex.budget.persistence.repository;

import com.yottaflex.budget.persistence.entity.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountRepository extends JpaRepository<AccountEntity, String> {
    List<AccountEntity> findByUser_UserIdOrderByAccountId(String userId);
}
