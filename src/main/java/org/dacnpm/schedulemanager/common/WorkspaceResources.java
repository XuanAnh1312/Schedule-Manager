package org.dacnpm.schedulemanager.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serve only public web assets; never expose the repository root or .env. */
@Configuration
public class WorkspaceResources implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String webRoot=java.nio.file.Path.of(".").toAbsolutePath().normalize().toUri().toString();
        if(!webRoot.endsWith("/"))webRoot+="/";
        registry.addResourceHandler("/js/**").addResourceLocations(webRoot+"js/", "classpath:/static/js/");
        registry.addResourceHandler("/css/**").addResourceLocations(webRoot+"css/", "classpath:/static/css/");
        registry.addResourceHandler("/index.html", "/workspace.html", "/attendance.html").addResourceLocations(webRoot, "classpath:/static/");
    }
}
