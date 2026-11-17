package app.orrery.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** In the production jar the UI is served from /static; client-side routes load index.html. */
@Controller
public class SpaController {

    @GetMapping({"/c/{id}", "/memory", "/ledger"})
    public String app() {
        return "forward:/index.html";
    }
}
