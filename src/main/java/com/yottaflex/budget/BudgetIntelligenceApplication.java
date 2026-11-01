package com.yottaflex.budget;

import com.yottaflex.budget.cli.Cli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. With a CLI command (generate | validate | evaluate | repro) it runs offline without starting
 * Spring or touching the database; with no command it starts the REST API backed by PostgreSQL.
 */
@SpringBootApplication
public class BudgetIntelligenceApplication {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && Cli.COMMANDS.contains(args[0])) {
            System.exit(Cli.run(args));
        }
        SpringApplication.run(BudgetIntelligenceApplication.class, args);
    }
}
