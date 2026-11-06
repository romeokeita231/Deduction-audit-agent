package com.deductionaudit.eval;

import com.deductionaudit.eval.corpus.CorpusLoader;
import com.deductionaudit.eval.corpus.CorpusLoader.LoadedCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

@Component
@Profile("live")
public class LiveEvalRunner implements ApplicationRunner {

    static final Path RESULTS = Path.of("target", "eval", "results.ndjson");

    private final CorpusLoader corpus;
    private final EvalCaseDriver driver;
    private final Clock clock;
    private final ApplicationContext context;

    public LiveEvalRunner(CorpusLoader corpus, EvalCaseDriver driver, Clock clock,
                          ApplicationContext context) {
        this.corpus = corpus;
        this.driver = driver;
        this.clock = clock;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<LoadedCase> corpusCases = corpus.load(clock);
        Integer limit = limit(args);
        int expected = EvalCaseDriver.expectedRowCount(limit, corpusCases.size());
        List<LoadedCase> cases = limit == null ? corpusCases : corpusCases.subList(0, expected);
        List<NdjsonExporter.EvalRow> rows = driver.runAll(cases, EvalCaseDriver.MAX_CONCURRENCY);
        EvalCaseDriver.assertRowCount(rows, expected);
        NdjsonExporter.write(RESULTS, rows, false);
        System.out.println("eval:live wrote " + rows.size() + " rows to " + RESULTS.toAbsolutePath());

        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }

    private static Integer limit(ApplicationArguments args) {
        if (!args.containsOption("limit")) {
            return null;
        }
        int limit = Integer.parseInt(args.getOptionValues("limit").getLast());
        if (limit < 1) {
            throw new IllegalArgumentException("the run limit must be positive");
        }
        return limit;
    }
}
