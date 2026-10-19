package com.xiaoli.aiagent.domain.dto;
import jakarta.validation.constraints.*;

/** 账号接口请求对象，Controller 通过 @Valid 执行边界校验。 */
public final class AccountRequests {
    private AccountRequests() { }
    public record Login(@NotBlank @Size(max=254) String email, @NotBlank @Size(max=64) String password) { }
    public record CodeLogin(@NotBlank @Size(max=254) String email, @Pattern(regexp="\\d{6}") @NotNull String code) { }
    public record Register(@NotBlank @Size(max=254) String email, @Pattern(regexp="\\d{6}") @NotNull String code,
                           @NotBlank @Size(max=64) String password, @NotBlank @Size(max=64) String confirmPassword,
                           @NotBlank @Size(max=40) String displayName) { }
    public record Nickname(@NotBlank @Size(max=40) String displayName) { }
    public record Code(@NotBlank @Size(max=254) String email, @NotBlank String purpose) { }
    public record Reset(@NotBlank @Size(max=254) String email, @NotBlank String code,
                        @NotBlank @Size(max=64) String password, @NotBlank @Size(max=64) String confirmPassword) { }
    public record Change(@NotBlank @Size(max=64) String currentPassword, @NotBlank @Size(max=64) String password,
                         @NotBlank @Size(max=64) String confirmPassword) { }
}
