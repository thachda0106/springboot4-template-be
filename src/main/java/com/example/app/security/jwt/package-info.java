/**
 * JWT issuance and validation machinery: the token service, the decoder configs,
 * the role-to-authority converter and the mode validator. Exposed to other modules
 * through the named interface {@code jwt} — {@link com.example.app.security.jwt.JwtTokenService}
 * is used by the user module's token adapter.
 */
@NamedInterface("jwt")
package com.example.app.security.jwt;

import org.springframework.modulith.NamedInterface;
