package org.wordpress.android.fluxc.store;

import android.support.annotation.NonNull;

import com.android.volley.VolleyError;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.wordpress.android.fluxc.network.discovery.SelfHostedEndpointFinder;
import org.wordpress.android.fluxc.network.discovery.SelfHostedEndpointFinder.DiscoveryError;
import org.wordpress.android.fluxc.network.discovery.SelfHostedEndpointFinder.DiscoveryResultPayload;
import org.wordpress.android.fluxc.network.rest.wpcom.account.AccountRestClient;
import org.wordpress.android.fluxc.network.rest.wpcom.account.AccountRestClient.AccountPostSettingsResponsePayload;
import org.wordpress.android.fluxc.network.rest.wpcom.account.AccountRestClient.AccountRestPayload;
import org.wordpress.android.fluxc.network.rest.wpcom.account.AccountRestClient.NewAccountResponsePayload;
import org.wordpress.android.fluxc.network.rest.wpcom.auth.Authenticator;
import org.wordpress.android.fluxc.network.rest.wpcom.auth.Authenticator.AuthenticateErrorPayload;
import org.wordpress.android.fluxc.network.rest.wpcom.auth.Authenticator.Token;
import org.wordpress.android.fluxc.Dispatcher;
import org.wordpress.android.fluxc.Payload;
import org.wordpress.android.fluxc.action.AccountAction;
import org.wordpress.android.fluxc.action.AuthenticationAction;
import org.wordpress.android.fluxc.annotations.action.Action;
import org.wordpress.android.fluxc.model.AccountModel;
import org.wordpress.android.fluxc.network.rest.wpcom.auth.AccessToken;
import org.wordpress.android.fluxc.persistence.AccountSqlUtils;
import org.wordpress.android.fluxc.store.SiteStore.RefreshSitesXMLRPCPayload;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * In-memory based and persisted in SQLite.
 */
@Singleton
public class AccountStore extends Store {
    // Payloads
    public static class AuthenticatePayload implements Payload {
        public String username;
        public String password;
        public Action nextAction;
        public AuthenticatePayload(@NonNull String username, @NonNull String password) {
            this.username = username;
            this.password = password;
        }
    }

    public static class PostAccountSettingsPayload implements Payload {
        public Map<String, String> params;
        public PostAccountSettingsPayload() {
        }
    }

    public static class NewAccountPayload implements Payload {
        public String username;
        public String password;
        public String email;
        public boolean dryRun;
        public NewAccountPayload(@NonNull String username, @NonNull String password, @NonNull String email,
                                 boolean dryRun) {
            this.username = username;
            this.password = password;
            this.email = email;
            this.dryRun = dryRun;
        }
    }

    public static class UpdateTokenPayload implements Payload {
        public UpdateTokenPayload(String token) { this.token = token; }
        public String token;
    }

    // OnChanged Events
    public class OnAccountChanged extends OnChanged {
        public boolean isError;
        public AccountError errorType;
        public boolean accountInfosChanged;
        public AccountAction causeOfChange;
    }

    public class OnAuthenticationChanged extends OnChanged {
        public boolean isError;
        public AuthenticationError errorType;
        public String errorMessage;
    }

    public class OnDiscoverySucceeded extends OnChanged {
        public String xmlRpcEndpoint;
        public String wpRestEndpoint;
    }

    public class OnDiscoveryFailed extends OnChanged {
        public DiscoveryError error;
        public String failedEndpoint;
    }

    public class OnNewUserCreated extends OnChanged {
        public boolean isError;
        public NewUserError errorType;
        public String errorMessage;
        public boolean dryRun;
    }

    // Enums
    public enum AuthenticationError {
        // From response's "error" field
        ACCESS_DENIED,
        AUTHORIZATION_REQUIRED,
        INVALID_CLIENT,
        INVALID_GRANT,
        INVALID_OTP,
        INVALID_REQUEST,
        INVALID_TOKEN,
        NEEDS_2FA,
        UNSUPPORTED_GRANT_TYPE,
        UNSUPPORTED_RESPONSE_TYPE,
        UNKNOWN_TOKEN,

        // From response's "message" field - sadly... (be careful with i18n)
        INCORRECT_USERNAME_OR_PASSWORD,

        // .org specifics
        INVALID_SSL_CERTIFICATE,
        HTTP_AUTH_ERROR,
        NOT_AUTHENTICATED,

        // Generic error
        GENERIC_ERROR;

        public static AuthenticationError fromString(String string) {
            if (string != null) {
                for (AuthenticationError v : AuthenticationError.values()) {
                    if (string.equalsIgnoreCase(v.name())) {
                        return v;
                    }
                }
            }
            return GENERIC_ERROR;
        }
    }

    public enum AccountError {
        ACCOUNT_FETCH_ERROR,
        SETTINGS_FETCH_ERROR,
        SETTINGS_POST_ERROR,
        GENERIC_ERROR
    }

    public enum NewUserError {
        USERNAME_ONLY_LOWERCASE_LETTERS_AND_NUMBERS,
        USERNAME_REQUIRED,
        USERNAME_NOT_ALLOWED,
        USERNAME_MUST_BE_AT_LEAST_FOUR_CHARACTERS,
        USERNAME_CONTAINS_INVALID_CHARACTERS,
        USERNAME_MUST_INCLUDE_LETTERS,
        USERNAME_EXISTS,
        USERNAME_RESERVED_BUT_MAY_BE_AVAILABLE,
        USERNAME_INVALID,
        PASSWORD_INVALID,
        EMAIL_CANT_BE_USED_TO_SIGNUP,
        EMAIL_INVALID,
        EMAIL_NOT_ALLOWED,
        EMAIL_EXISTS,
        EMAIL_RESERVED,
        GENERIC_ERROR;

        public static NewUserError fromString(String string) {
            if (string != null) {
                for (NewUserError v : NewUserError.values()) {
                    if (string.equalsIgnoreCase(v.name())) {
                        return v;
                    }
                }
            }
            return GENERIC_ERROR;
        }
    }

    // Fields
    private AccountRestClient mAccountRestClient;
    private Authenticator mAuthenticator;
    private AccountModel mAccount;
    private AccessToken mAccessToken;
    private SelfHostedEndpointFinder mSelfHostedEndpointFinder;

    @Inject
    public AccountStore(Dispatcher dispatcher, AccountRestClient accountRestClient,
                        SelfHostedEndpointFinder selfHostedEndpointFinder, Authenticator authenticator,
                        AccessToken accessToken) {
        super(dispatcher);
        mAuthenticator = authenticator;
        mAccountRestClient = accountRestClient;
        mSelfHostedEndpointFinder = selfHostedEndpointFinder;
        mAccount = loadAccount();
        mAccessToken = accessToken;
    }

    @Override
    public void onRegister() {
        AppLog.d(T.API, "AccountStore onRegister");
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    @Override
    public void onAction(Action action) {
        org.wordpress.android.fluxc.annotations.action.IAction actionType = action.getType();
        if (actionType == AuthenticationAction.AUTHENTICATE_ERROR) {
            OnAuthenticationChanged event = new OnAuthenticationChanged();
            AuthenticateErrorPayload payload = (AuthenticateErrorPayload) action.getPayload();
            event.isError = true;
            event.errorMessage = payload.errorMessage;
            event.errorType = payload.errorType;
            emitChange(event);
        } else if (actionType == AuthenticationAction.AUTHENTICATE) {
            AuthenticatePayload payload = (AuthenticatePayload) action.getPayload();
            authenticate(payload.username, payload.password, payload);
        } else if (actionType == AuthenticationAction.DISCOVER_ENDPOINT) {
            RefreshSitesXMLRPCPayload payload = (RefreshSitesXMLRPCPayload) action.getPayload();
            mSelfHostedEndpointFinder.findEndpoint(payload.url, payload.username, payload.password);
        } else if (actionType == AuthenticationAction.DISCOVERY_RESULT) {
            DiscoveryResultPayload payload = (DiscoveryResultPayload) action.getPayload();
            if (payload.isError) {
                OnDiscoveryFailed discoveryFailed = new OnDiscoveryFailed();
                discoveryFailed.error = payload.error;
                discoveryFailed.failedEndpoint = payload.failedEndpoint;
                emitChange(discoveryFailed);
            } else {
                OnDiscoverySucceeded discoverySucceeded = new OnDiscoverySucceeded();
                discoverySucceeded.xmlRpcEndpoint = payload.xmlRpcEndpoint;
                discoverySucceeded.wpRestEndpoint = payload.wpRestEndpoint;
                emitChange(discoverySucceeded);
            }
        } else if (actionType == AccountAction.FETCH_ACCOUNT) {
            // fetch only Account
            mAccountRestClient.fetchAccount();
        } else if (actionType == AccountAction.FETCH_SETTINGS) {
            // fetch only Account Settings
            mAccountRestClient.fetchAccountSettings();
        } else if (actionType == AccountAction.POST_SETTINGS) {
            PostAccountSettingsPayload payload = (PostAccountSettingsPayload) action.getPayload();
            mAccountRestClient.postAccountSettings(payload.params);
        } else if (actionType == AccountAction.FETCHED_ACCOUNT) {
            AccountRestPayload data = (AccountRestPayload) action.getPayload();
            if (!checkError(data, "Error fetching Account via REST API (/me)")) {
                mAccount.copyAccountAttributes(data.account);
                updateDefaultAccount(mAccount, AccountAction.FETCH_ACCOUNT);
            } else {
                emitAccountChangeError(AccountError.ACCOUNT_FETCH_ERROR);
            }
        } else if (actionType == AccountAction.FETCHED_SETTINGS) {
            AccountRestPayload data = (AccountRestPayload) action.getPayload();
            if (!checkError(data, "Error fetching Account Settings via REST API (/me/settings)")) {
                mAccount.copyAccountSettingsAttributes(data.account);
                updateDefaultAccount(mAccount, AccountAction.FETCH_SETTINGS);
            } else {
                emitAccountChangeError(AccountError.SETTINGS_FETCH_ERROR);
            }
        } else if (actionType == AccountAction.POSTED_SETTINGS) {
            AccountPostSettingsResponsePayload data = (AccountPostSettingsResponsePayload) action.getPayload();
            if (!data.isError()) {
                boolean updated = AccountRestClient.updateAccountModelFromPostSettingsResponse(mAccount, data.settings);
                if (updated) {
                    updateDefaultAccount(mAccount, AccountAction.POST_SETTINGS);
                } else {
                    OnAccountChanged accountChanged = new OnAccountChanged();
                    accountChanged.accountInfosChanged = false;
                    emitChange(accountChanged);
                }
            } else {
                emitAccountChangeError(AccountError.SETTINGS_POST_ERROR);
            }
        } else if (actionType == AccountAction.UPDATE_ACCOUNT) {
            AccountModel accountModel = (AccountModel) action.getPayload();
            updateDefaultAccount(accountModel, AccountAction.UPDATE_ACCOUNT);
        } else if (actionType == AccountAction.UPDATE_ACCESS_TOKEN) {
            UpdateTokenPayload updateTokenPayload = (UpdateTokenPayload) action.getPayload();
            updateToken(updateTokenPayload);
        } else if (actionType == AccountAction.SIGN_OUT) {
            signOut();
        } else if (actionType == AccountAction.CREATE_NEW_ACCOUNT) {
            newAccount((NewAccountPayload) action.getPayload());
        } else if (actionType == AccountAction.CREATED_NEW_ACCOUNT) {
            NewAccountResponsePayload payload = (NewAccountResponsePayload) action.getPayload();
            OnNewUserCreated onNewUserCreated = new OnNewUserCreated();
            onNewUserCreated.isError = payload.isError;
            onNewUserCreated.errorType = payload.errorType;
            onNewUserCreated.errorMessage = payload.errorMessage;
            onNewUserCreated.dryRun = payload.dryRun;
            emitChange(onNewUserCreated);
        }
    }

    private void emitAccountChangeError(AccountError errorType) {
        OnAccountChanged accountChanged = new OnAccountChanged();
        accountChanged.isError = true;
        accountChanged.errorType = errorType;
        emitChange(accountChanged);
    }

    private void newAccount(NewAccountPayload payload) {
        mAccountRestClient.newAccount(payload.username, payload.password, payload.email, payload.dryRun);
    }

    private void signOut() {
        // Remove Account
        AccountSqlUtils.deleteAccount(mAccount);
        mAccount.init();
        OnAccountChanged accountChanged = new OnAccountChanged();
        accountChanged.accountInfosChanged = true;
        emitChange(accountChanged);
        // Remove authentication token
        mAccessToken.set(null);
        emitChange(new OnAuthenticationChanged());
    }

    public AccountModel getAccount() {
        return mAccount;
    }

    /**
     * Can be used to check if Account is signed into WordPress.com.
     */
    public boolean hasAccessToken() {
        return mAccessToken.exists();
    }

    /**
     * Should be used for very specific purpose (like forwarding the token to a Webview)
     */
    public String getAccessToken() {
        return mAccessToken.get();
    }

    private void updateToken(UpdateTokenPayload updateTokenPayload) {
        mAccessToken.set(updateTokenPayload.token);
    }

    private void updateDefaultAccount(AccountModel accountModel, AccountAction cause) {
        // Update memory instance
        mAccount = accountModel;
        AccountSqlUtils.insertOrUpdateDefaultAccount(accountModel);
        OnAccountChanged accountChanged = new OnAccountChanged();
        accountChanged.accountInfosChanged = true;
        accountChanged.causeOfChange = cause;
        emitChange(accountChanged);
    }

    private AccountModel loadAccount() {
        AccountModel account = AccountSqlUtils.getDefaultAccount();
        return account == null ? new AccountModel() : account;
    }

    private void authenticate(String username, String password, final AuthenticatePayload payload) {
        mAuthenticator.authenticate(username, password, null, false, new Authenticator.Listener() {
            @Override
            public void onResponse(Token token) {
                mAccessToken.set(token.getAccessToken());
                if (payload.nextAction != null) {
                    mDispatcher.dispatch(payload.nextAction);
                }
                emitChange(new OnAuthenticationChanged());
            }
        }, new Authenticator.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(T.API, "Authentication error");
                OnAuthenticationChanged event = new OnAuthenticationChanged();
                event.errorType = Authenticator.volleyErrorToAuthenticationError(volleyError);
                event.errorMessage = Authenticator.volleyErrorToErrorMessage(volleyError);
                event.isError = true;
                emitChange(event);
            }
        });
    }

    private boolean checkError(AccountRestPayload payload, String log) {
        if (payload.isError()) {
            AppLog.w(T.API, log + "\nError: " + payload.error.getMessage());
            return true;
        }
        return false;
    }
}