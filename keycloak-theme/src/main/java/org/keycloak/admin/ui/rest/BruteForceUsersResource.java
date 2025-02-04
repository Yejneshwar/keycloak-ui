package org.keycloak.admin.ui.rest;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;
import org.keycloak.admin.ui.rest.model.BruteUser;
import org.keycloak.common.util.Time;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserLoginFailureModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.services.managers.BruteForceProtector;
import org.keycloak.services.resources.admin.permissions.AdminPermissionEvaluator;
import org.keycloak.services.resources.admin.permissions.UserPermissionEvaluator;
import org.keycloak.utils.SearchQueryUtils;

@Path("/")
public class BruteForceUsersResource {
    private static final Logger logger = Logger.getLogger(BruteForceUsersResource.class);
    private static final String SEARCH_ID_PARAMETER = "id:";
    @Context
    private KeycloakSession session;
    private final RealmModel realm;
    private final AdminPermissionEvaluator auth;

    public BruteForceUsersResource(RealmModel realm, AdminPermissionEvaluator auth) {
        this.realm = realm;
        this.auth = auth;
    }

    @GET
    @Consumes({"application/json"})
    @Produces({"application/json"})
    @Operation(
            summary = "Find all users and add if they are locked by brute force protection",
            description = "Same endpoint as the users search but added brute force protection status."
    )
    @APIResponse(
            responseCode = "200",
            description = "",
            content = {@Content(
                    schema = @Schema(
                            implementation = BruteUser.class,
                            type = SchemaType.ARRAY
                    )
            )}
    )
    public final Stream<BruteUser> searchUser(@QueryParam("search") String search,
            @QueryParam("lastName") String last,
            @QueryParam("firstName") String first,
            @QueryParam("email") String email,
            @QueryParam("username") String username,
            @QueryParam("emailVerified") Boolean emailVerified,
            @QueryParam("phoneNumberLocale") String phoneNumberLocale,
            @QueryParam("phoneNumber") String phoneNumber,
            @QueryParam("phoneNumberVerified") Boolean phoneNumberVerified,
            @QueryParam("idpAlias") String idpAlias,
            @QueryParam("idpUserId") String idpUserId,
            @QueryParam("first") @DefaultValue("-1") Integer firstResult,
            @QueryParam("max") @DefaultValue("" + Constants.DEFAULT_MAX_RESULTS) Integer maxResults,
            @QueryParam("enabled") Boolean enabled,
            @QueryParam("briefRepresentation") Boolean briefRepresentation,
            @QueryParam("exact") Boolean exact,
            @QueryParam("q") String searchQuery) {
        final UserPermissionEvaluator userPermissionEvaluator = auth.users();
        userPermissionEvaluator.requireQuery();

        Map<String, String> searchAttributes = searchQuery == null
                ? Collections.emptyMap()
                : SearchQueryUtils.getFields(searchQuery);

        Stream<UserModel> userModels = Stream.empty();
        if (search != null) {
            if (search.startsWith(SEARCH_ID_PARAMETER)) {
                UserModel userModel =
                        session.users().getUserById(realm, search.substring(SEARCH_ID_PARAMETER.length()).trim());
                if (userModel != null) {
                    userModels = Stream.of(userModel);
                }
            } else {
                Map<String, String> attributes = new HashMap<>();
                attributes.put(UserModel.SEARCH, search.trim());
                if (enabled != null) {
                    attributes.put(UserModel.ENABLED, enabled.toString());
                }
                return searchForUser(attributes, realm, userPermissionEvaluator, briefRepresentation, firstResult,
                        maxResults, false);
            }
        } else if (last != null || first != null || email != null || username != null || emailVerified != null || phoneNumberVerified != null 
                || idpAlias != null || idpUserId != null || enabled != null || phoneNumberLocale != null || phoneNumber != null || exact != null || !searchAttributes.isEmpty()) {
            Map<String, String> attributes = new HashMap<>();
            if (last != null) {
                attributes.put(UserModel.LAST_NAME, last);
            }
            if (first != null) {
                attributes.put(UserModel.FIRST_NAME, first);
            }
            if (email != null) {
                attributes.put(UserModel.EMAIL, email);
            }
            if (phoneNumberLocale != null) {
                attributes.put(UserModel.PHONE_NUMBER_LOCALE, phoneNumberLocale);
            }
            if (phoneNumber != null) {
                attributes.put(UserModel.PHONE_NUMBER, phoneNumber);
            }
            if (username != null) {
                attributes.put(UserModel.USERNAME, username);
            }
            if (emailVerified != null) {
                attributes.put(UserModel.EMAIL_VERIFIED, emailVerified.toString());
            }
            if (phoneNumberVerified != null) {
                attributes.put(UserModel.PHONE_NUMBER_VERIFIED, phoneNumberVerified.toString());
            }
            if (idpAlias != null) {
                attributes.put(UserModel.IDP_ALIAS, idpAlias);
            }
            if (idpUserId != null) {
                attributes.put(UserModel.IDP_USER_ID, idpUserId);
            }
            if (enabled != null) {
                attributes.put(UserModel.ENABLED, enabled.toString());
            }
            if (exact != null) {
                attributes.put(UserModel.EXACT, exact.toString());
            }

            attributes.putAll(searchAttributes);

            return searchForUser(attributes, realm, userPermissionEvaluator, briefRepresentation, firstResult,
                    maxResults, true);
        } else {
            return searchForUser(new HashMap<>(), realm, userPermissionEvaluator, briefRepresentation,
                    firstResult, maxResults, false);
        }

        return toRepresentation(realm, userPermissionEvaluator, briefRepresentation, userModels);

    }

    private Stream<BruteUser> searchForUser(Map<String, String> attributes, RealmModel realm, UserPermissionEvaluator usersEvaluator, Boolean briefRepresentation, Integer firstResult, Integer maxResults, Boolean includeServiceAccounts) {
        session.setAttribute(UserModel.INCLUDE_SERVICE_ACCOUNT, includeServiceAccounts);

        if (!auth.users().canView()) {
            Set<String> groupModels = auth.groups().getGroupsWithViewPermission();

            if (!groupModels.isEmpty()) {
                session.setAttribute(UserModel.GROUPS, groupModels);
            }
        }

        Stream<UserModel> userModels = session.users().searchForUserStream(realm, attributes, firstResult, maxResults);
        return toRepresentation(realm, usersEvaluator, briefRepresentation, userModels);
    }

    private Stream<BruteUser> toRepresentation(RealmModel realm, UserPermissionEvaluator usersEvaluator,
            Boolean briefRepresentation, Stream<UserModel> userModels) {
        boolean briefRepresentationB = briefRepresentation != null && briefRepresentation;
        boolean canViewGlobal = usersEvaluator.canView();

        usersEvaluator.grantIfNoPermission(session.getAttribute(UserModel.GROUPS) != null);
        return userModels.filter(user -> canViewGlobal || usersEvaluator.canView(user)).map(user -> {
            UserRepresentation userRep = briefRepresentationB ?
                    ModelToRepresentation.toBriefRepresentation(user) :
                    ModelToRepresentation.toRepresentation(session, realm, user);
            userRep.setAccess(usersEvaluator.getAccess(user));
            return userRep;
        }).map(this::getBruteForceStatus);
    }

    private BruteUser getBruteForceStatus(UserRepresentation user) {
        BruteUser bruteUser = new BruteUser(user);
        Map<String, Object> data = new HashMap<>();
        data.put("disabled", false);
        data.put("numFailures", 0);
        data.put("lastFailure", 0);
        data.put("lastIPFailure", "n/a");
        if (!realm.isBruteForceProtected())
            bruteUser.setBruteForceStatus(data);

        UserLoginFailureModel model = session.loginFailures().getUserLoginFailure(realm, user.getId());
        if (model == null) {
            bruteUser.setBruteForceStatus(data);
            return bruteUser;
        }

        boolean disabled;
        disabled = isTemporarilyDisabled(session, realm, user);
        if (disabled) {
            data.put("disabled", true);
        }

        data.put("numFailures", model.getNumFailures());
        data.put("lastFailure", model.getLastFailure());
        data.put("lastIPFailure", model.getLastIPFailure());
        bruteUser.setBruteForceStatus(data);

        return bruteUser;
    }

    public boolean isTemporarilyDisabled(KeycloakSession session, RealmModel realm, UserRepresentation user) {
        UserLoginFailureModel failure = session.loginFailures().getUserLoginFailure(realm, user.getId());
        if (failure != null) {
            int currTime = (int)(Time.currentTimeMillis() / 1000L);
            int failedLoginNotBefore = failure.getFailedLoginNotBefore();
            if (currTime < failedLoginNotBefore) {
                logger.debugv("Current: {0} notBefore: {1}", currTime, failedLoginNotBefore);
                return true;
            }
        }

        return false;
    }
}
