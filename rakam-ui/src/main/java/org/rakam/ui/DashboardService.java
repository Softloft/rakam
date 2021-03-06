/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.rakam.ui;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.IgnoreApi;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.ui.UIPermissionParameterProvider.Project;
import org.rakam.util.AlreadyExistsException;
import org.rakam.util.JsonHelper;
import org.rakam.util.RakamException;
import org.rakam.util.SuccessMessage;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.util.IntegerMapper;
import org.skife.jdbi.v2.util.LongMapper;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Path;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static java.lang.Boolean.TRUE;

// todo check permissions
@Path("/ui/dashboard")
@IgnoreApi
public class DashboardService
        extends HttpService
{
    private final DBI dbi;

    @Inject
    public DashboardService(@Named("ui.metadata.jdbc") JDBCPoolDataSource dataSource)
    {
        dbi = new DBI(dataSource);
    }

    @JsonRequest
    @ApiOperation(value = "Create dashboard")
    @Path("/create")
    @ProtectEndpoint(writeOperation = true)
    public Dashboard create(
            @Named("user_id") Project project,
            @ApiParam("name") String name,
            @ApiParam(value = "shared_everyone", required = false) Boolean sharedEveryone,
            @ApiParam(value = "options", required = false) Map<String, Object> options)
    {
        try (Handle handle = dbi.open()) {
            int id;
            try {
                id = handle.createQuery("INSERT INTO public.dashboard (project_id, name, user_id, options) VALUES (:project, :name, :user, :options) RETURNING id")
                        .bind("project", project.project)
                        .bind("user", project.userId)
                        .bind("options", JsonHelper.encode(options))
                        .bind("name", name).map(IntegerMapper.FIRST).first();
            }
            catch (Exception e) {
                if (handle.createQuery("SELECT 1 FROM public.dashboard WHERE (project_id, name) = (:project, :name)")
                        .bind("project", project.project)
                        .bind("name", name).first() != null) {
                    throw new AlreadyExistsException("Dashboard", BAD_REQUEST);
                }

                throw e;
            }
            return new Dashboard(id, project.userId, name, null, options, TRUE.equals(sharedEveryone));
        }
    }

    @JsonRequest
    @ApiOperation(value = "Get Report")
    @Path("/get")
    public List<DashboardItem> get(@Named("user_id") Project project, @ApiParam("id") int id)
    {
        try (Handle handle = dbi.open()) {
            return handle.createQuery("SELECT id, name, directive, options, refresh_interval, last_updated," +
                    "(case when refresh_interval is null or now() - last_updated > refresh_interval * INTERVAL '1 second' then null else data end)" +
                    " FROM public.dashboard_items WHERE dashboard = (SELECT id FROM public.dashboard WHERE project_id = :project AND id = :id)")
                    .bind("project", project.project)
                    .bind("id", id)
                    .map((i, r, statementContext) -> {
                        return new DashboardItem(r.getInt(1),
                                r.getString(2), r.getString(3),
                                JsonHelper.read(r.getString(4), Map.class),
                                r.getObject(5) == null ? null : Duration.ofSeconds(r.getInt(5)),
                                r.getTimestamp(6) != null ? r.getTimestamp(6).toInstant() : null, r.getBytes(7));
                    }).list();
        }
    }

    @JsonRequest
    @ApiOperation(value = "Get dashboard users")
    @Path("/users")
    public List<DashboardPermission> getUsers(@Named("user_id") Project project, @ApiParam("id") int id)
    {
        try (Handle handle = dbi.open()) {
            return handle.createQuery("SELECT web_user.id, web_user.email, permission.shared_at" +
                    " FROM public.dashboard_permission permission " +
                    " JOIN public.web_user ON (permission.user_id = web_user.id) " +
                    " WHERE dashboard = (SELECT id FROM public.dashboard WHERE project_id = :project AND id = :id)")
                    .bind("project", project.project)
                    .bind("id", id)
                    .map((i, r, statementContext) -> {
                        return new DashboardPermission(r.getInt(1), r.getString(2), r.getTimestamp(3).toInstant());
                    }).list();
        }
    }

    @JsonRequest
    @ApiOperation(value = "Get dashboard users")
    @Path("/users/add")
    public SuccessMessage addUser(@Named("user_id") Project project, @ApiParam("dashboard") int id, @ApiParam("user_id") int user)
    {
        try (Handle handle = dbi.open()) {
            handle.createStatement("INSERT INTO public.dashboard_permission (dashboard, user_id) SELECT :dashboard, :shared_user FROM public.dashboard WHERE id = :dashboard AND user_id = :dashboard_user AND (select count(*) from dashboard_permission where dashboard = :dashboard and user_id = :shared_user) = 0")
                    .bind("dashboard", id)
                    .bind("shared_user", user)
                    .bind("dashboard_user", project.userId)
                    .execute();
            return SuccessMessage.success();
        }
    }

    @JsonRequest
    @ApiOperation(value = "Get dashboard users")
    @Path("/users/remove")
    public SuccessMessage removeUser(@Named("user_id") Project project, @ApiParam("dashboard") int id, @ApiParam("user_id") int user)
    {
        try (Handle handle = dbi.open()) {
            handle.createStatement("DELETE FROM public.dashboard_permission WHERE dashboard = :dashboard AND user_id = :user AND (select bool_or(true) from public.dashboard where user_id = :dashboard_user and id = :dashboard)")
                    .bind("shared_user", user)
                    .bind("dashboard", id)
                    .bind("dashboard_user", project.userId)
                    .bind("user", user)
                    .execute();
            return SuccessMessage.success();
        }
    }

    public static class DashboardPermission
    {
        public final int id;
        public final String email;
        public final Instant sharedAt;

        public DashboardPermission(int id, String email, Instant sharedAt)
        {
            this.id = id;
            this.email = email;
            this.sharedAt = sharedAt;
        }
    }

    @JsonRequest
    @ApiOperation(value = "Cache report data")
    @Path("/cache-item-data")
    public SuccessMessage cache(
            @Named("user_id") Project project,
            @ApiParam("item_id") int item_id,
            @ApiParam("data") byte[] data)
    {
        try (Handle handle = dbi.open()) {
            handle.createStatement("UPDATE public.dashboard_items SET data = :data, last_updated = now() WHERE id = :id AND" +
                    " (SELECT project_id FROM public.dashboard_items item JOIN public.dashboard ON (dashboard.id = item.dashboard) WHERE item.id = :id AND dashboard.project_id = :project) is not null")
                    .bind("id", item_id)
                    .bind("project", project.project)
                    .bind("data", data).execute();
            return SuccessMessage.success();
        }
    }

    @JsonRequest
    @ApiOperation(value = "List Report")
    @Path("/list")
    public List<Dashboard> list(@Named("user_id") Project project)
    {
        try (Handle handle = dbi.open()) {
            return handle.createQuery("SELECT id, name, refresh_interval, options FROM public.dashboard WHERE project_id = :project ORDER BY id")
                    .bind("project", project.project).map((i, resultSet, statementContext) -> {
                        Map options = JsonHelper.read(resultSet.getString(4), Map.class);
                        return new Dashboard(resultSet.getInt(1), project.userId, resultSet.getString(2),
                                resultSet.getObject(3) == null ? null : Duration.ofSeconds(resultSet.getInt(3)),
                                options == null ? null : options, resultSet.getBoolean(4));
                    }).list();
        }
    }

    public static class Dashboard
    {
        public final int id;
        public final int userId;
        public final String name;
        public final Map<String, Object> options;
        public final Duration refresh_interval;
        public final boolean sharedEveryone;

        @JsonCreator
        public Dashboard(int id, int userId, String name, Duration refresh_interval, Map<String, Object> options, boolean sharedEveryone)
        {
            this.id = id;
            this.name = name;
            this.userId = userId;
            this.refresh_interval = refresh_interval;
            this.options = options;
            this.sharedEveryone = sharedEveryone;
        }
    }

    public static class DashboardItem
    {
        public final Integer id;
        public final Map options;
        public final Duration refreshInterval;
        public final String directive;
        public final String name;
        public final Instant lastUpdated;
        public final byte[] data;

        @JsonCreator
        public DashboardItem(
                @JsonProperty("id") Integer id,
                @JsonProperty("name") String name,
                @JsonProperty("directive") String directive,
                @JsonProperty("options") Map options,
                @JsonProperty("refreshInterval") Duration refreshInterval,
                @JsonProperty("data") byte[] data)
        {
            this(id, name, directive, options, refreshInterval, null, data);
        }

        public DashboardItem(Integer id, String name, String directive, Map options, Duration refreshInterval,
                Instant lastUpdated, byte[] data)
        {
            this.id = id;
            this.options = options;
            this.refreshInterval = refreshInterval;
            this.directive = directive;
            this.name = name;
            this.lastUpdated = lastUpdated;
            this.data = data;
        }
    }

    @JsonRequest
    @ApiOperation(value = "Add item to dashboard")
    @Path("/add_item")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage addToDashboard(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("name") String itemName,
            @ApiParam("directive") String directive,
            @ApiParam(value = "refresh_interval", required = false) Duration refreshInterval,
            @ApiParam("options") Map options)
    {
        try (Handle handle = dbi.open()) {
            handle.createStatement("INSERT INTO public.dashboard_items (dashboard, name, directive, options, refresh_interval) VALUES (:dashboard, :name, :directive, :options, :refreshInterval)")
                    .bind("project", project.project)
                    .bind("dashboard", dashboard)
                    .bind("name", itemName)
                    .bind("directive", directive)
                    .bind("refreshInterval", Optional.ofNullable(refreshInterval).map(e -> e.getSeconds()).orElse(null))
                    .bind("options", JsonHelper.encode(options)).execute();
        }
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Update dashboard items")
    @Path("/update_dashboard_items")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage updateDashboard(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("items") List<DashboardItem> items)
    {
        dbi.inTransaction((handle, transactionStatus) -> {
            Long execute = handle.createQuery("SELECT id FROM public.dashboard WHERE id = :id AND project_id = :project")
                    .bind("id", dashboard)
                    .bind("project", project.project)
                    .map(LongMapper.FIRST).first();

            if (execute == null) {
                throw new RakamException(HttpResponseStatus.NOT_FOUND);
            }

            for (DashboardItem item : items) {
                // TODO: verify dashboard is in project
                handle.createStatement("UPDATE public.dashboard_items SET name = :name, directive = :directive, options = :options WHERE id = :id")
                        .bind("id", item.id)
                        .bind("name", item.name)
                        .bind("directive", item.directive)
                        .bind("options", JsonHelper.encode(item.options))
                        .execute();
            }
            return null;
        });
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Update dashboard options")
    @Path("/update_dashboard_options")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage updateDashboardOptions(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("name") String name,
            @ApiParam("refresh_interval") Duration refreshDuration,
            @ApiParam(value = "sharedEveryone", required = false) Boolean sharedEveryone,
            @ApiParam("options") Map<String, Object> options)
    {
        dbi.inTransaction((handle, transactionStatus) -> {
            handle.createStatement("UPDATE public.dashboard SET options = :options, refresh_interval = :refreshDuration, name = :name," +
                    " shared_everyone = (case when :shared is null then shared_everyone else :shared end)" +
                    " WHERE id = :id AND project_id = :project")
                    .bind("id", dashboard)
                    .bind("name", name)
                    .bind("shared", TRUE.equals(sharedEveryone))
                    .bind("refreshDuration", refreshDuration.getSeconds())
                    .bind("options", JsonHelper.encode(options))
                    .bind("project", project.project)
                    .execute();
            return null;
        });
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Rename dashboard item")
    @Path("/rename_item")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage renameDashboardItem(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("id") int id,
            @ApiParam("name") String name)
    {
        try (Handle handle = dbi.open()) {
            // todo: check project
            handle.createStatement("UPDATE public.dashboard_items SET name = :name WHERE id = :id")
                    .bind("id", id)
                    .bind("name", name).execute();
        }
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Delete dashboard item")
    @Path("/delete_item")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage removeFromDashboard(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("id") int id)
    {
        try (Handle handle = dbi.open()) {
            handle.createStatement("DELETE FROM public.dashboard_items " +
                    "WHERE dashboard = :dashboard AND id = :id")
                    .bind("project", project.project)
                    .bind("dashboard", dashboard)
                    .bind("id", id).execute();
        }
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Delete dashboard item")
    @Path("/delete")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage delete(@Named("user_id") Project project,
            @ApiParam("id") int dashboard)
    {
        try (Handle handle = dbi.open()) {
            int execute = handle.createStatement("DELETE FROM public.dashboard WHERE id = :id and project_id = :project AND " +
                    "(select count(*) FROM public.dashboard WHERE project_id = :project) > 1")
                    .bind("id", dashboard).bind("project", project.project).execute();
            if (execute == 0) {
                throw new RakamException("You cannot remove the single dashboard.", BAD_REQUEST);
            }
        }
        return SuccessMessage.success();
    }
}
