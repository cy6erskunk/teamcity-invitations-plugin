package org.jetbrains.teamcity.invitations;

import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.auth.AuthorityHolder;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.serverSide.auth.Role;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.web.util.SessionUser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

import static java.util.stream.Collectors.toList;

public class JoinProjectInvitationType implements InvitationType<JoinProjectInvitationType.InvitationImpl> {

    private final TeamCityCoreFacade core;

    public JoinProjectInvitationType(TeamCityCoreFacade core) {
        this.core = core;
    }

    @NotNull
    @Override
    public String getId() {
        return "joinProjectInvitation";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Invite user to join a project";
    }

    @NotNull
    @Override
    public String getDescriptionViewPath() {
        return core.getPluginResourcesPath("joinProjectInvitationDescription.jsp");
    }

    @NotNull
    @Override
    public ModelAndView getEditPropertiesView(@Nullable InvitationImpl invitation) {
        ModelAndView modelAndView = new ModelAndView(core.getPluginResourcesPath("joinProjectInvitationProperties.jsp"));
        modelAndView.getModel().put("name", invitation == null ? "Join Project Invitation" : invitation.getName());
        modelAndView.getModel().put("roles", core.getAvailableRoles().stream().filter(Role::isProjectAssociationSupported).collect(toList()));
        modelAndView.getModel().put("name", invitation == null ? "Join Project Invitation" : invitation.getName());
        modelAndView.getModel().put("multiuser", invitation == null ? "true" : invitation.multi);
        modelAndView.getModel().put("roleId", invitation == null ? "PROJECT_DEVELOPER" : invitation.roleId);
        return modelAndView;
    }

    @NotNull
    @Override
    public InvitationImpl createNewInvitation(@NotNull HttpServletRequest request, @NotNull SProject project, @NotNull String token) {
        String name = request.getParameter("name");
        String roleId = request.getParameter("role");
        boolean multiuser = Boolean.parseBoolean(request.getParameter("multiuser"));
        return createNewInvitation(SessionUser.getUser(request), name, token, project.getExternalId(), roleId, multiuser);
    }

    @NotNull
    public InvitationImpl createNewInvitation(SUser inviter, String name, String token, String projectExtId, String roleId, boolean multiuser) {
        return new InvitationImpl(inviter, name, token, projectExtId, roleId, multiuser);
    }

    @NotNull
    @Override
    public InvitationImpl readFrom(@NotNull Map<String, String> params, @NotNull SProject project) {
        try {
            return new InvitationImpl(params, project);
        } catch (Exception e) {
            Loggers.SERVER.warnAndDebugDetails("Unable to load the invitation", e);
            return null;
        }
    }

    @Override
    public boolean isAvailableFor(AuthorityHolder authorityHolder, @NotNull SProject project) {
        return authorityHolder.isPermissionGrantedForProject(project.getProjectId(), Permission.CHANGE_USER_ROLES_IN_PROJECT);
    }

    public final class InvitationImpl extends AbstractInvitation {

        @NotNull
        private final String projectExtId;
        @NotNull
        private final String roleId;

        InvitationImpl(@NotNull SUser currentUser, @NotNull String name, @NotNull String token, @NotNull String projectExtId, @NotNull String roleId, boolean multi) {
            super(name, token, multi, JoinProjectInvitationType.this, currentUser.getId());
            this.roleId = roleId;
            this.projectExtId = projectExtId;
        }

        public InvitationImpl(Map<String, String> params, SProject project) {
            super(params, JoinProjectInvitationType.this);
            this.projectExtId = project.getExternalId();
            this.roleId = params.get("roleId");
        }

        @NotNull
        @Override
        protected String getLandingPage() {
            return core.getPluginResourcesPath("joinProjectInvitationLanding.jsp");
        }

        @NotNull
        @Override
        public Map<String, String> asMap() {
            Map<String, String> result = super.asMap();
            result.put("roleId", roleId);
            return result;
        }

        @Override
        public boolean isAvailableFor(@NotNull AuthorityHolder user) {
            return user.isPermissionGrantedForProject(getProject().getProjectId(), Permission.CHANGE_USER_ROLES_IN_PROJECT);
        }

        @NotNull
        public ModelAndView userRegistered(@NotNull SUser user, @NotNull HttpServletRequest request, @NotNull HttpServletResponse response) {
            try {
                Role role = getRole();
                if (role == null) {
                    throw new InvitationException("Failed to proceed invitation with a non-existing role " + roleId);
                }

                SProject project = getProject();
                if (project == null) {
                    throw new InvitationException("Failed to proceed invitation with a non-existing project " + projectExtId);
                }

                core.addRoleAsSystem(user, role, project);
                Loggers.SERVER.info("User " + user.describe(false) + " registered on invitation '" + token + "'. " +
                        "User got the role " + role.describe(false) + " in the project " + project.describe(false));

                if (role.getPermissions().contains(Permission.EDIT_PROJECT)) {
                    return new ModelAndView(new RedirectView("/editProject.html?projectId=" + project.getExternalId(), true));
                }
                return new ModelAndView(new RedirectView("/project.html?projectId=" + project.getExternalId(), true));
            } catch (Exception e) {
                Loggers.SERVER.warn("Failed to create project for the invited user " + user.describe(false), e);
                return new ModelAndView(new RedirectView("/", true));
            }
        }

        @Nullable
        public Role getRole() {
            return JoinProjectInvitationType.this.core.findRoleById(roleId);
        }

        @NotNull
        public SProject getProject() {
            return JoinProjectInvitationType.this.core.findProjectByExtId(projectExtId);
        }

        @Nullable
        public SUser getUser() {
            return JoinProjectInvitationType.this.core.getUser(createdByUserId);
        }
    }
}
