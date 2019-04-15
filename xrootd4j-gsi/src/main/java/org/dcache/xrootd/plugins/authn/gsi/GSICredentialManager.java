/**
 * Copyright (C) 2011-2019 dCache.org <support@dcache.org>
 *
 * This file is part of xrootd4j.
 *
 * xrootd4j is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * xrootd4j is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with xrootd4j.  If not, see http://www.gnu.org/licenses/.
 */
package org.dcache.xrootd.plugins.authn.gsi;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import eu.emi.security.authn.x509.CrlCheckingMode;
import eu.emi.security.authn.x509.NamespaceCheckingMode;
import eu.emi.security.authn.x509.OCSPCheckingMode;
import eu.emi.security.authn.x509.OCSPParametes;
import eu.emi.security.authn.x509.ProxySupport;
import eu.emi.security.authn.x509.RevocationParameters;
import eu.emi.security.authn.x509.X509CertChainValidator;
import eu.emi.security.authn.x509.X509Credential;
import eu.emi.security.authn.x509.helpers.ssl.HostnameToCertificateChecker;
import eu.emi.security.authn.x509.helpers.trust.OpensslTruststoreHelper;
import eu.emi.security.authn.x509.impl.OpensslCertChainValidator;
import eu.emi.security.authn.x509.impl.PEMCredential;
import eu.emi.security.authn.x509.impl.ValidatorParams;
import eu.emi.security.authn.x509.proxy.ProxyCertificate;
import eu.emi.security.authn.x509.proxy.ProxyCertificateOptions;
import eu.emi.security.authn.x509.proxy.ProxyGenerator;
import eu.emi.security.authn.x509.proxy.ProxyRequestOptions;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.x500.X500Principal;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.dcache.xrootd.core.XrootdException;
import org.dcache.xrootd.plugins.CredentialStoreClient;
import org.dcache.xrootd.util.ProxyRequest;

import static org.dcache.xrootd.plugins.CredentialStoreClient.MINIMUM_VALID_FOR;
import static org.dcache.xrootd.plugins.CredentialStoreClient.MINIMUM_VALID_FOR_UNIT;
import static org.dcache.xrootd.protocol.XrootdProtocol.kXR_ServerError;
import static org.dcache.xrootd.protocol.XrootdProtocol.kXR_error;

/**
 *  <p>Supports credential loading and creation for both server and client.</p>
 *
 *  <p>Also supports calls to credential store client in support of direct
 *     proxy delegation.</p>
 *
 *  <p>Initializes the certificate objects (host certificate, host key,
 *      trusted certificates and CRLs) needed for handlers to perform their tasks.</p>
 *
 *  <p>Thus the certificates and trust anchors can be cached for a configurable
 *      time period. The configuration option controlling this caching is the
 *      same as the one used in the SRM door.</p>
 */
public class GSICredentialManager
{
    private static final Logger                       LOGGER
                    = LoggerFactory.getLogger(GSICredentialManager.class);

    private static final HostnameToCertificateChecker CERT_CHECKER =
                    new HostnameToCertificateChecker();

    public static void checkIdentity(X509Certificate certificate, String name)
                    throws GeneralSecurityException, UnknownHostException {
        if (certificate.getSubjectDN().getName().contains(name) ||
                        CERT_CHECKER.checkMatching(name, certificate)) {
            return;
        }

        String error = "The name of the source server does not match any subject "
                        + "name of the received credential.";
        throw new GeneralSecurityException(error);
    }

    /*
     *  Local credentials, CA certs and voms dir.
     */
    private final String                 caCertificatePath;
    private final X509CertChainValidator certChainValidator;
    private final long                   trustAnchorRefreshInterval;
    private final String                 hostCertificatePath;
    private final String                 hostKeyPath;
    private final long                   hostCertRefreshInterval;
    private final boolean                verifyHostCertificate;
    private final String                 clientCertificatePath;
    private final String                 clientKeyPath;
    private final long                   proxyRefreshInterval;
    private final boolean                verifyClientCertificate;
    private final String                 proxyPath;

    private long   hostCertRefreshTimestamp = 0;
    private long   proxyRefreshTimestamp    = 0;
    private String clientCredIssuerHashes;

    private PEMCredential  hostCredential;
    private PEMCredential  clientCredential;
    private X509Credential proxy;

    /*
     *  For delegated proxy request
     */
    private X509CredentialStoreClient               credentialStoreClient;
    private ProxyRequest<X509Certificate[], String> proxyRequest;
    private X509Certificate[]                       clientCertChain;

    public GSICredentialManager(Properties properties)
    {
        caCertificatePath = properties.getProperty("xrootd.gsi.ca.path");
        trustAnchorRefreshInterval =
                        TimeUnit.valueOf(properties.getProperty("xrootd.gsi.ca.refresh.unit"))
                                .toMillis(Integer.parseInt(properties.getProperty("xrootd.gsi.ca.refresh")));
        NamespaceCheckingMode namespaceMode =
                        NamespaceCheckingMode.valueOf(properties.getProperty("xrootd.gsi.ca.namespace-mode"));
        CrlCheckingMode crlCheckingMode =
                        CrlCheckingMode.valueOf(properties.getProperty("xrootd.gsi.ca.crl-mode"));
        OCSPCheckingMode ocspCheckingMode =
                        OCSPCheckingMode.valueOf(properties.getProperty("xrootd.gsi.ca.ocsp-mode"));
        ValidatorParams validatorParams = new ValidatorParams(
                        new RevocationParameters(crlCheckingMode, new OCSPParametes(ocspCheckingMode)), ProxySupport.ALLOW);
        certChainValidator = new OpensslCertChainValidator(caCertificatePath, false, namespaceMode,
                                                           trustAnchorRefreshInterval, validatorParams, false);

        /**
         *  Local host
         */
        hostKeyPath = properties.getProperty("xrootd.gsi.hostcert.key");
        hostCertificatePath = properties.getProperty("xrootd.gsi.hostcert.cert");
        hostCertRefreshInterval =
                        TimeUnit.valueOf(properties.getProperty("xrootd.gsi.hostcert.refresh.unit"))
                                .toMillis(Integer.parseInt(properties.getProperty("xrootd.gsi.hostcert.refresh")));
        verifyHostCertificate =
                        Boolean.parseBoolean(properties.getProperty("xrootd.gsi.hostcert.verify"));

        /**
         *  If dCache third-party copy properties are locally defined
         */
        clientKeyPath = properties.getProperty("xrootd.gsi.tpc.cred.key");
        clientCertificatePath = properties.getProperty("xrootd.gsi.tpc.cred.cert");
        proxyRefreshInterval =
                        TimeUnit.valueOf(properties.getProperty("xrootd.gsi.tpc.cred.refresh.unit"))
                                .toMillis(Integer.parseInt(properties.getProperty("xrootd.gsi.tpc.cred.refresh")));
        verifyClientCertificate =
                        Boolean.parseBoolean(properties.getProperty("xrootd.gsi.tpc.cred.verify"));
        proxyPath = properties.getProperty("xrootd.gsi.tpc.proxy.path");
    }

    public synchronized void cancelOutstandingProxyRequest()
    {
        if (proxyRequest != null && proxyRequest.getId() != null) {
            try {
                credentialStoreClient.cancelProxyRequest(proxyRequest);
            } catch (XrootdException e) {
                LOGGER.warn("Problem cancelling proxy delegation request {} {}: {}.",
                            proxyRequest.getKey()[0].getSubjectDN(),
                            proxyRequest.getId(),
                            e.toString());
            }
            proxyRequest = null;
        }
    }

    public void checkCaIdentities(String[] caIdentities)
                    throws XrootdException
    {
        for (String ca : caIdentities)
        {
            if (!isValidCaPath(ca)) {
                throw new XrootdException(kXR_error, ca
                                + " is not a valid ca cert path.");
            }
        }
    }

    public synchronized boolean hasValidDelegatedProxy(X509Certificate[] certChain)
                    throws XrootdException
    {
        return credentialStoreClient().fetchCredential(certChain,
                                                       MINIMUM_VALID_FOR,
                                                       MINIMUM_VALID_FOR_UNIT)
                                      .isPresent();
    }

    /**
     * Attempts to store the new proxy.
     *
     * @param proxyCert signed by client.
     */
    public synchronized void finalizeDelegatedProxy(X509Certificate proxyCert)
                    throws XrootdException
    {
        if (proxyRequest == null) {
            throw new XrootdException(kXR_ServerError, "cannot finalize proxy: "
                            + "proxy request was not sent.");
        }

        String serializedCert
                        = CertUtil.chainToPEM(CertUtil.prepend(proxyCert,
                                                               clientCertChain));

        credentialStoreClient().storeCredential(clientCertChain,
                                                proxyRequest.getId(),
                                                serializedCert);

        /*
         *  This call is understood to be the last in a sequence
         *  for any given GSI exchange.
         */
        proxyRequest = null;
    }

    /**
     * Server-side method.
     *
     * Create a proxy request (CSR) from the client's certificate chain.
     *
     * Also stores the cert chain and proxy request
     * for future processing/finalization.
     *
     * @param certChain from authenticating client.
     * @return byte array representing the CSR (for inclusion in message to
     *         client).
     */
    public synchronized byte[] prepareSerializedProxyRequest(X509Certificate[] certChain)
                    throws XrootdException {
        clientCertChain = certChain;
        proxyRequest = credentialStoreClient().getProxyRequest(certChain);
        return proxyRequest.getRequest().getBytes();
    }

    /**
     * Client-side method.
     *
     * Takes the CSR request from the server, and adds the new signed certificate
     * based on it to the top/front of the certificate chain.
     *
     * NOTA BENE:  This method is here only for completeness.  Hopefully,
     * the SLAC server will be smart enough to know not to request
     * a delegated proxy from the TPC client.  When talking to a dCache
     * door, this should always be the case, as the source server will
     * have already authenticated the user client and checked for/requested
     * a proxy then, which hopefully would be found in cache on the TPC client
     * call.
     *
     * @param serverCSR
     * @return full cert chain with chain[0] equal to the new signed cert.
     */
    public synchronized X509Certificate[] getSignedProxyRequest(byte[] serverCSR)
                    throws IOException, NoSuchAlgorithmException,
                    SignatureException, InvalidKeyException,
                    CertificateParsingException, NoSuchProviderException
    {
        ProxyRequestOptions options = new ProxyRequestOptions(
                        proxy.getCertificateChain(),
                        new PKCS10CertificationRequest(serverCSR));
        return ProxyGenerator.generate(options, proxy.getKey());
    }

    /**
     * Client-side, will attempt to read in a prefetched proxy from a given
     * path, or to construct one from the local cert and key, if refresh
     * has expired.
     */
    public synchronized void loadClientCredentials()
    {
        try {
            if (shouldRefreshClientProxyCredential()) {
                LOGGER.info("Refreshing proxy credential. Current refresh interval: {} ms",
                            proxyRefreshInterval);

                if (!Strings.isNullOrEmpty(proxyPath)) {
                    clientCredential = new PEMCredential(proxyPath, (char[]) null);
                    proxy = clientCredential;
                } else {
                    clientCredential = new PEMCredential(clientKeyPath,
                                                         clientCertificatePath,
                                                         null);
                    if (verifyClientCertificate) {
                        LOGGER.info("Verifying client certificate");
                        certChainValidator.validate(clientCredential.getCertificateChain());
                    }

                    /*
                     *  SLAC server requires an actual proxy, that is,
                     *  cert chain length > 1.
                     */
                    try {
                        ProxyCertificateOptions options
                                        = new ProxyCertificateOptions(
                                        clientCredential.getCertificateChain());
                        ProxyCertificate proxyCert = ProxyGenerator.generate(
                                        options,
                                        clientCredential.getKey());
                        proxy = proxyCert.getCredential();
                    } catch (InvalidKeyException | SignatureException | NoSuchAlgorithmException e) {
                        throw new CertificateException(
                                        "could not generate host proxy credential.",
                                        e);
                    }
                }

                proxyRefreshTimestamp = System.currentTimeMillis();
            }
        } catch (GeneralSecurityException gssex) {
            LOGGER.error("Could not load certificates/key due to security error; {}: {}.",
                         getCredentialValues(), gssex.toString());
        } catch (IOException ioex) {
            LOGGER.error("Could not read certificates/key from file-system; {}: {}.",
                         getCredentialValues(), ioex.toString());

        }

        clientCredIssuerHashes = generateIssuerHashes();
    }

    /**
     * Server-side, will attempt to generate host credential from
     * local cert and key, if refresh has expired.
     */
    public synchronized void loadServerCredentials()
                    throws CertificateException, KeyStoreException, IOException
    {
        if (shouldReloadServerCredentials()) {
            LOGGER.info("Loading server certificates. Current refresh interval: {} ms",
                        hostCertRefreshInterval);
            PEMCredential credential = new PEMCredential(hostKeyPath,
                                                         hostCertificatePath,
                                                         null);
            if (verifyHostCertificate) {
                LOGGER.info("Verifying host certificate");
                certChainValidator.validate(credential.getCertificateChain());
            }
            hostCredential = credential;
            hostCertRefreshTimestamp = System.currentTimeMillis();
        }
    }

    public String getCaCertificatePath() {
        return caCertificatePath;
    }

    public X509CertChainValidator getCertChainValidator() {
        return certChainValidator;
    }

    public long getTrustAnchorRefreshInterval() {
        return trustAnchorRefreshInterval;
    }

    public String getHostCertificatePath() {
        return hostCertificatePath;
    }

    public String getHostKeyPath() {
        return hostKeyPath;
    }

    public long getHostCertRefreshInterval() {
        return hostCertRefreshInterval;
    }

    public boolean isVerifyHostCertificate() {
        return verifyHostCertificate;
    }

    public String getClientCertificatePath() {
        return clientCertificatePath;
    }

    public String getClientCredIssuerHashes()
    {
        return clientCredIssuerHashes;
    }

    public String getClientKeyPath() {
        return clientKeyPath;
    }

    public long getProxyRefreshInterval() {
        return proxyRefreshInterval;
    }

    public boolean isVerifyClientCertificate() {
        return verifyClientCertificate;
    }

    public String getProxyPath() {
        return proxyPath;
    }

    public PEMCredential getHostCredential() {
        return hostCredential;
    }

    public PEMCredential getClientCredential() {
        return clientCredential;
    }

    public X509Credential getProxy()
    {
        return proxy;
    }

    public long getHostCertRefreshTimestamp()
    {
        return hostCertRefreshTimestamp;
    }

    public long getProxyRefreshTimestamp() {
        return proxyRefreshTimestamp;
    }

    public void validate(X509Certificate[] proxyCertChain)
    {
        certChainValidator.validate(proxyCertChain);
    }

    public void setCredentialStoreClient(CredentialStoreClient credentialStoreClient)
    {
        this.credentialStoreClient = (X509CredentialStoreClient)credentialStoreClient;
    }

    private X509CredentialStoreClient credentialStoreClient() throws XrootdException
    {
        if (credentialStoreClient == null) {
            throw new XrootdException(kXR_ServerError, "no client to credential "
                            + "store has been provided.");
        }

        return credentialStoreClient;
    }

    private String generateIssuerHashes()
    {
        Set<String> issuers = new HashSet<>();

        for (X509Certificate certificate: proxy.getCertificateChain()) {
            X500Principal certIssuer = certificate.getIssuerX500Principal();
            issuers.add(OpensslTruststoreHelper.getOpenSSLCAHash(certIssuer,
                                                                 true));
        }

        return Joiner.on("|").join(issuers);
    }

    private String getCredentialValues()
    {
        return "client cert path: " + clientCertificatePath
                        + ", client key path: " + clientKeyPath
                        + ", client issuer hashes: " + clientCredIssuerHashes
                        + ", proxy path: " + proxyPath;
    }

    private boolean isValidCaPath(String path)
    {
        path = path.trim();

        if (path.indexOf(".") < 1) {
            path += ".0";
        }

        return new File(caCertificatePath, path).exists();
    }

    private boolean shouldReloadServerCredentials()
    {
        long timeSinceLastServerRefresh = (System.currentTimeMillis() - hostCertRefreshTimestamp);
        LOGGER.info("Time since last server cert refresh {}", timeSinceLastServerRefresh);
        return hostCredential == null || timeSinceLastServerRefresh >= hostCertRefreshInterval;
    }

    private boolean shouldRefreshClientProxyCredential()
    {
        long timeSinceLastClientRefresh = (System.currentTimeMillis() - proxyRefreshTimestamp);
        LOGGER.info("Time since last client cert refresh {}", timeSinceLastClientRefresh);
        return proxy == null || timeSinceLastClientRefresh >= proxyRefreshInterval;
    }
}
