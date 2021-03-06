// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2007 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package com.netscape.ca;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SignatureException;

import org.mozilla.jss.CryptoManager;
import org.mozilla.jss.NoSuchTokenException;
import org.mozilla.jss.crypto.CryptoToken;
import org.mozilla.jss.crypto.ObjectNotFoundException;
import org.mozilla.jss.crypto.PrivateKey;
import org.mozilla.jss.crypto.Signature;
import org.mozilla.jss.crypto.SignatureAlgorithm;
import org.mozilla.jss.crypto.TokenException;
import org.mozilla.jss.crypto.X509Certificate;
import org.mozilla.jss.util.IncorrectPasswordException;
import org.mozilla.jss.util.PasswordCallback;

import com.netscape.certsrv.apps.CMS;
import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.IConfigStore;
import com.netscape.certsrv.base.ISubsystem;
import com.netscape.certsrv.ca.CAMissingCertException;
import com.netscape.certsrv.ca.CAMissingKeyException;
import com.netscape.certsrv.ca.ECAException;
import com.netscape.certsrv.logging.ILogger;
import com.netscape.certsrv.security.ISigningUnit;
import com.netscape.cms.logging.Logger;
import com.netscape.cmscore.security.JssSubsystem;
import com.netscape.cmsutil.crypto.CryptoUtil;
import com.netscape.cmsutil.util.Cert;

import netscape.security.x509.AlgorithmId;
import netscape.security.x509.X509CertImpl;
import netscape.security.x509.X509Key;

/**
 * CA signing unit based on JSS.
 *
 * $Revision$ $Date$
 */

public final class SigningUnit implements ISigningUnit {
    public static final String PROP_DEFAULT_SIGNALG = "defaultSigningAlgorithm";
    public static final String PROP_CERT_NICKNAME = "cacertnickname";
    // This signing unit is being used in OCSP and CRL also. So
    // it is better to have a more generic name
    public static final String PROP_RENAMED_CERT_NICKNAME = "certnickname";
    public static final String PROP_TOKEN_NAME = "tokenname";
    public static final String PROP_NEW_NICKNAME = "newNickname";

    private CryptoManager mManager = null;
    private CryptoToken mToken = null;
    private PublicKey mPubk = null;
    private PrivateKey mPrivk = null;

    protected X509Certificate mCert = null;
    protected X509CertImpl mCertImpl = null;
    protected String mNickname = null;

    private boolean mInited = false;
    private Logger systemLogger = Logger.getLogger(ILogger.EV_SYSTEM, ILogger.S_CA);
    private IConfigStore mConfig;

    @SuppressWarnings("unused")
    private ISubsystem mOwner;

    private String mDefSigningAlgname = null;
    private SignatureAlgorithm mDefSigningAlgorithm = null;

    public SigningUnit() {
    }

    public X509Certificate getCert() {
        return mCert;
    }

    public X509CertImpl getCertImpl() {
        return mCertImpl;
    }

    public String getNickname() {
        return mNickname;
    }

    public String getNewNickName() throws EBaseException {
        return mConfig.getString(PROP_NEW_NICKNAME, "");
    }

    public void setNewNickName(String name) {
        mConfig.putString(PROP_NEW_NICKNAME, name);
    }

    public PublicKey getPublicKey() {
        return mPubk;
    }

    public PrivateKey getPrivateKey() {
        return mPrivk;
    }

    public void updateConfig(String nickname, String tokenname) {
        mConfig.putString(PROP_CERT_NICKNAME, nickname);
        mConfig.putString(PROP_TOKEN_NAME, tokenname);
    }

    public String getTokenName() throws EBaseException {
        return mConfig.getString(PROP_TOKEN_NAME);
    }


    public void init(ISubsystem owner, IConfigStore config)
            throws EBaseException {
        init(owner, config, null);
    }

    public void init(ISubsystem owner, IConfigStore config, String nickname)
            throws EBaseException {

        CMS.debug("CA SigningUnit.init(" + owner.getId() + ", " + config.getName() + ", " + nickname + ")");

        mOwner = owner;
        mConfig = config;

        String tokenname = null;
        try {
            mManager = CryptoManager.getInstance();

            if (nickname == null) {
                try {
                    mNickname = mConfig.getString(PROP_RENAMED_CERT_NICKNAME);
                } catch (EBaseException e) {
                    mNickname = mConfig.getString(PROP_CERT_NICKNAME);
                }
            } else {
                mNickname = nickname;
            }

            tokenname = config.getString(PROP_TOKEN_NAME);
            mToken = CryptoUtil.getKeyStorageToken(tokenname);
            if (CryptoUtil.isInternalToken(tokenname)) {
                setNewNickName(mNickname);
            } else {
                mNickname = tokenname + ":" + mNickname;
                setNewNickName(mNickname);
            }

            CMS.debug("SigningUnit: Logging into token " + tokenname);
            PasswordCallback cb = JssSubsystem.getInstance().getPWCB();
            mToken.login(cb); // ONE_TIME by default.

            try {
                CMS.debug("SigningUnit: Loading certificate " + mNickname);
                mCert = mManager.findCertByNickname(mNickname);
                CMS.debug("SigningUnit: certificate serial number: " + mCert.getSerialNumber());

            } catch (ObjectNotFoundException e) {
                CMS.debug("SigningUnit: Unable to find certificate " + mNickname);
                throw new CAMissingCertException(CMS.getUserMessage("CMS_CA_CERT_OBJECT_NOT_FOUND"), e);
            }

            mCertImpl = new X509CertImpl(mCert.getEncoded());

            try {
                CMS.debug("SigningUnit: Loading private key");
                mPrivk = mManager.findPrivKeyByCert(mCert);

            } catch (ObjectNotFoundException e) {
                CMS.debug("SigningUnit: Unable to find private key for " + mNickname);
                throw new CAMissingKeyException(CMS.getUserMessage("CMS_CA_CERT_OBJECT_NOT_FOUND"), e);
            }

            String privateKeyID = CryptoUtil.byte2string(mPrivk.getUniqueID());
            CMS.debug("SigningUnit: private key ID: " + privateKeyID);

            mPubk = mCert.getPublicKey();

            // get def alg and check if def sign alg is valid for token.
            mDefSigningAlgname = config.getString(PROP_DEFAULT_SIGNALG);
            mDefSigningAlgorithm = checkSigningAlgorithmFromName(mDefSigningAlgname);
            CMS.debug("SigningUnit: signing algorithm: " + mDefSigningAlgorithm);

            mInited = true;

        } catch (java.security.cert.CertificateException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_CA_SIGNING_CA_CERT", e.getMessage()));
            throw new ECAException(CMS.getUserMessage("CMS_BASE_INTERNAL_ERROR", e.toString()), e);

        } catch (CryptoManager.NotInitializedException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_CA_SIGNING_TOKEN_INIT", e.toString()));
            throw new ECAException(CMS.getUserMessage("CMS_CA_CRYPTO_NOT_INITIALIZED"), e);

        } catch (IncorrectPasswordException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_CA_SIGNING_WRONG_PWD", e.toString()));
            throw new ECAException(CMS.getUserMessage("CMS_CA_INVALID_PASSWORD"), e);

        } catch (NoSuchTokenException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_CA_SIGNING_TOKEN_NOT_FOUND", tokenname, e.toString()));
            throw new ECAException(CMS.getUserMessage("CMS_CA_TOKEN_NOT_FOUND", tokenname), e);

        } catch (CAMissingCertException | CAMissingKeyException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_CA_SIGNING_CERT_NOT_FOUND", e.toString()));
            throw e;  // re-throw

        } catch (TokenException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("OPERATION_ERROR", e.toString()));
            throw new ECAException(CMS.getUserMessage("CMS_CA_TOKEN_ERROR"), e);
        }
    }

    /**
     * Check if the signing algorithm name is supported and valid for this
     * signing unit's token and key.
     *
     * @param algname a signing algorithm name from JCA.
     * @return the mapped JSS signature algorithm object.
     *
     * @exception EBaseException if signing algorithm is not supported.
     */
    public SignatureAlgorithm checkSigningAlgorithmFromName(String algname)
            throws EBaseException {
        try {
            SignatureAlgorithm sigalg = null;

            sigalg = mapAlgorithmToJss(algname);
            if (sigalg == null) {
                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_CA_SIGNING_ALG_NOT_SUPPORTED", algname, ""));
                throw new ECAException(
                        CMS.getUserMessage("CMS_CA_SIGNING_ALGOR_NOT_SUPPORTED", algname));
            }
            Signature signer = mToken.getSignatureContext(sigalg);

            signer.initSign(mPrivk);
            return sigalg;
        } catch (NoSuchAlgorithmException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_CA_SIGNING_ALG_NOT_SUPPORTED", algname, e.toString()));
            throw new ECAException(
                    CMS.getUserMessage("CMS_CA_SIGNING_ALGOR_NOT_SUPPORTED", algname), e);
        } catch (TokenException e) {
            // from get signature context or from initSign
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_CA_SIGNING_ALG_NOT_SUPPORTED", algname, e.toString()));
            throw new ECAException(
                    CMS.getUserMessage("CMS_CA_SIGNING_ALGOR_NOT_SUPPORTED", algname), e);
        } catch (InvalidKeyException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_CA_SIGNING_ALG_NOT_SUPPORTED", algname, e.toString()));
            throw new ECAException(
                    CMS.getUserMessage("CMS_CA_SIGNING_ALGOR_NOT_SUPPORTED_FOR_KEY", algname), e);
        }
    }

    /**
     * @param algname is expected to be one of JCA's algorithm names.
     */
    public byte[] sign(byte[] data, String algname)
            throws EBaseException {
        if (!mInited) {
            throw new EBaseException("CASigningUnit not initialized!");
        }
        try {
            // XXX for now do this mapping until James changes the names
            // to match JCA names and provide a getAlgorithm method.
            SignatureAlgorithm signAlg = mDefSigningAlgorithm;

            if (algname != null) {
                signAlg = checkSigningAlgorithmFromName(algname);
            }

            // XXX use a pool of signers based on alg ?
            // XXX Map algor. name to id. hack: use hardcoded define for now.
            CMS.debug(
                    "Getting algorithm context for " + algname + " " + signAlg);
            Signature signer = mToken.getSignatureContext(signAlg);

            signer.initSign(mPrivk);
            signer.update(data);

            /* debugging
            boolean testAutoShutdown = false;
            testAutoShutdown = mConfig.getBoolean("autoShutdown.test", false);
            if (testAutoShutdown) {
                CMS.debug("SigningUnit.sign: test auto shutdown");
                CMS.checkForAndAutoShutdown();
            }
            */

            // XXX add something more descriptive.
            CMS.debug("Signing Certificate");
            return signer.sign();
        } catch (NoSuchAlgorithmException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("OPERATION_ERROR", e.toString()));
            throw new ECAException(
                    CMS.getUserMessage("CMS_CA_SIGNING_ALGOR_NOT_SUPPORTED", algname), e);
        } catch (TokenException e) {
            // from get signature context or from initSign
            log(ILogger.LL_FAILURE, CMS.getLogMessage("OPERATION_ERROR", e.toString()));
            // XXX fix this exception later.
            throw new EBaseException(e);
        } catch (InvalidKeyException e) {
            // XXX fix this exception later.
            throw new EBaseException(e);
        } catch (SignatureException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("OPERATION_ERROR", e.toString()));
            CMS.debug("SigningUnit.sign: " + e.toString());
            CMS.checkForAndAutoShutdown();
            // XXX fix this exception later.
            throw new EBaseException(e);
        }
    }

    public boolean verify(byte[] data, byte[] signature, String algname)
            throws EBaseException {
        if (!mInited) {
            throw new EBaseException("CASigningUnit not initialized!");
        }
        try {
            SignatureAlgorithm signAlg = mapAlgorithmToJss(algname);

            if (signAlg == null) {
                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_CA_SIGNING_ALG_NOT_SUPPORTED", algname, ""));
                throw new ECAException(
                        CMS.getUserMessage("CMS_CA_SIGNING_ALGOR_NOT_SUPPORTED", algname));
            }
            // XXX make this configurable. hack: use hardcoded for now.
            Signature signer = mToken.getSignatureContext(signAlg);

            signer.initVerify(mPubk);
            signer.update(data);
            return signer.verify(signature);
        } catch (NoSuchAlgorithmException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("OPERATION_ERROR", e.toString()));
            // XXX fix this exception later.
            throw new EBaseException(e);
        } catch (TokenException e) {
            // from get signature context or from initSign
            log(ILogger.LL_FAILURE, CMS.getLogMessage("OPERATION_ERROR", e.toString()));
            // XXX fix this exception later.
            throw new EBaseException(e);
        } catch (InvalidKeyException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("OPERATION_ERROR", e.toString()));
            // XXX fix this exception later.
            throw new EBaseException(e);
        } catch (SignatureException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("OPERATION_ERROR", e.toString()));
            CMS.checkForAndAutoShutdown();
            // XXX fix this exception later.
            throw new EBaseException(e);
        }
    }

    private void log(int level, String msg) {
        systemLogger.log(level, "CASigningUnit: " + msg);
    }

    /**
     * returns default signature algorithm
     */
    public SignatureAlgorithm getDefaultSignatureAlgorithm() {
        return mDefSigningAlgorithm;
    }

    /**
     * returns default signing algorithm name.
     */
    public String getDefaultAlgorithm() {
        return mDefSigningAlgname;
    }

    public void setDefaultAlgorithm(String algorithm) throws EBaseException {
        mConfig.putString(PROP_DEFAULT_SIGNALG, algorithm);
        mDefSigningAlgname = algorithm;
        log(ILogger.LL_INFO,
                "Default signing algorithm is set to " + algorithm);
    }

    /**
     * get all possible algorithms for the CA signing key type.
     */
    public String[] getAllAlgorithms() throws EBaseException {
        byte[] keybytes = mPubk.getEncoded();
        X509Key key = new X509Key();

        try {
            key.decode(keybytes);
        } catch (java.security.InvalidKeyException e) {
            String msg = "Invalid encoding in CA signing key.";

            log(ILogger.LL_FAILURE, CMS.getLogMessage("OPERATION_ERROR", msg));
            throw new EBaseException(CMS.getUserMessage("CMS_BASE_INTERNAL_ERROR", msg), e);
        }

        if (key.getAlgorithmId().getOID().equals(AlgorithmId.DSA_oid)) {
            return AlgorithmId.DSA_SIGNING_ALGORITHMS;
        } else {
            return AlgorithmId.ALL_SIGNING_ALGORITHMS;
        }
    }

    public static SignatureAlgorithm mapAlgorithmToJss(String algname) {
        return Cert.mapAlgorithmToJss(algname);
    }
}
