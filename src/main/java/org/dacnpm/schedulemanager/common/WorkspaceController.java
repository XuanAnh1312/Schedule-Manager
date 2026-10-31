package org.dacnpm.schedulemanager.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WorkspaceController {
    @GetMapping("/")
    public String home() { return "forward:/workspace.html"; }
}
