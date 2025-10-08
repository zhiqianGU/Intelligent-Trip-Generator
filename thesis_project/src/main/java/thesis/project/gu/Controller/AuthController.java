package thesis.project.gu.Controller;


import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import thesis.project.gu.req.LoginReq;
import thesis.project.gu.req.RegisterReq;
import thesis.project.gu.service.AuthService;

@RestController
@RequestMapping("/auth")
@Validated
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }
    record TokenResp(String token) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@RequestBody @Validated RegisterReq req) {
        authService.register(req.login(), req.password(), req.name());
    }

    @PostMapping("/login")
    public TokenResp login(@Valid @RequestBody LoginReq req) {
        String token = authService.login(req.login(), req.password());
        return new TokenResp(token);
    }
}

