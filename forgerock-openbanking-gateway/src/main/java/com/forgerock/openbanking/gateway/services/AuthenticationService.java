/**
 * Copyright 2019 ForgeRock AS.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.forgerock.openbanking.gateway.services;

import com.forgerock.openbanking.gateway.model.ApplicationIdentity;
import com.forgerock.openbanking.gateway.model.Tpp;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.Optional;

@Service
@Slf4j
public class AuthenticationService {

    @Autowired
    private RestTemplate restTemplate;
    @Value("${directory.endpoints.authenticate}")
    public String authenticateEndpoint;
    @Value("${rs-store.base-url}")
    private String rsStoreRoot;

    public Optional<Tpp> authenticateTPP(ServerHttpRequest request) {
        X509Certificate[] certs = request.getSslInfo().getPeerCertificates();
        if (null != certs && certs.length > 0) {
            X509Certificate x509Certificate = certs[0];

            if (x509Certificate != null) {
                try {
                    JWK jwk = JWK.parse(x509Certificate);
                    log.debug("Adding the client certificate subject {} in the header of the request", jwk.toJSONString());

                } catch (JOSEException e) {
                    log.error("Can't parse x509 certificate");
                }
            } else {
                log.debug("No client certificate received.");
            }
            try {
                ApplicationIdentity applicationIdentity = authenticate(JWK.parse(certs[0]));
                log.debug("applicationIdentity : {}", applicationIdentity);
                return findByCn(applicationIdentity.getId());
            } catch (JOSEException e) {
                log.error("Can't parse jwk from certificate '{}'", certs[0]);
            }
        }
        return Optional.empty();
    }

    public ApplicationIdentity authenticate(JWK jwk) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ParameterizedTypeReference<ApplicationIdentity> ptr = new ParameterizedTypeReference<ApplicationIdentity>() {
        };
        //TODO read the endpoint from the configuration
        HttpEntity<String> request = new HttpEntity<>(jwk.toJSONObject().toJSONString(), headers);

        ResponseEntity<ApplicationIdentity> entity = restTemplate.exchange(authenticateEndpoint,
                HttpMethod.POST, request, ptr);

        return entity.getBody();
    }

    private Optional<Tpp> findByCn(String cn) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(rsStoreRoot + "/tpps/search/findByCertificateCn");
        builder.queryParam("certificateCn", cn);
        URI uri = builder.build().encode().toUri();
        log.debug("Find cn {}", cn);
        try {
            ResponseEntity<Tpp> entity = restTemplate.exchange(uri, HttpMethod.GET, null, Tpp.class);
            return Optional.of(entity.getBody());

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

}
