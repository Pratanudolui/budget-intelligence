package com.yottaflex.budget.web;

import com.yottaflex.budget.service.DatasetLoaderService;
import com.yottaflex.budget.web.dto.LoadRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;

/** System administrator: seed the database and inspect table counts. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final DatasetLoaderService loader;

    public AdminController(DatasetLoaderService loader) {
        this.loader = loader;
    }

    @PostMapping("/datasets/load")
    public Map<String, Long> load(@Valid @RequestBody LoadRequest request,
                                  @RequestHeader(value = "X-Actor", defaultValue = "admin") String actor) throws Exception {
        return loader.load(Path.of(request.path()), actor);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return loader.tableCounts();
    }
}
