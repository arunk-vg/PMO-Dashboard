package net.valueglobal.jira.plugins.colorpiechart.gadgets;

import com.atlassian.jira.bc.JiraServiceContextImpl;
import com.atlassian.jira.bc.filter.SearchRequestService;
import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.charts.ChartFactory;
import com.atlassian.jira.charts.util.ChartUtils;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.issue.search.SearchRequest;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.rest.v1.model.errors.ErrorCollection;
import com.atlassian.jira.rest.v1.model.errors.ValidationError;
import com.atlassian.jira.rest.v1.util.CacheControl;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.util.MessageSet;
import com.atlassian.jira.util.collect.CollectionBuilder;
import com.atlassian.jira.util.velocity.VelocityRequestContextFactory;
import com.atlassian.query.QueryImpl;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.lang.StringUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.core.Response;

public class ProjectOrFilterIdBasedChartResource
{
    protected static final String INLINE = "inline";

    private static String MAX_DAYS_AP_PREFIX = "jira.chart.days.previous.limit.";

    final JiraAuthenticationContext jiraAuthenticationContext;

    final ProjectManager projectManager;

    final SearchRequestService searchRequestService;

    final SearchService searchService;

    final ChartUtils chartUtils;

    final PermissionManager permissionManager;

    final VelocityRequestContextFactory velocityRequestContextFactory;

    final ApplicationProperties applicationProperties;

    public ProjectOrFilterIdBasedChartResource(JiraAuthenticationContext jiraAuthenticationContext, ProjectManager projectManager, SearchRequestService searchRequestService, SearchService searchService, ChartUtils chartUtils, PermissionManager permissionManager, VelocityRequestContextFactory velocityRequestContextFactory, ApplicationProperties applicationProperties)
    {
        this.jiraAuthenticationContext = jiraAuthenticationContext;
        this.projectManager = projectManager;
        this.searchRequestService = searchRequestService;
        this.searchService = searchService;
        this.chartUtils = chartUtils;
        this.permissionManager = permissionManager;
        this.velocityRequestContextFactory = velocityRequestContextFactory;
        this.applicationProperties = applicationProperties;
    }

    void validateProjectOrFilterId(String projectOrFilterId, Collection<ValidationError> validationErrors)
    {
        if (StringUtils.isBlank(projectOrFilterId))
        {
            validationErrors.add(new ValidationError("projectOrFilterId", "gadget.common.required.query"));
            return;
        }

        SearchRequest searchRequest = chartUtils.retrieveOrMakeSearchRequest(projectOrFilterId, new HashMap<String, Object>());
        if (null != searchRequest)
        {
            MessageSet messageSet = searchService.validateQuery(jiraAuthenticationContext.getUser().getDirectoryUser(), searchRequest.getQuery());
            if (messageSet.hasAnyErrors())
            {
                for (String error :  messageSet.getErrorMessages())
                    validationErrors.add(new ValidationError("projectOrFilterId", "gadget.common.invalid.filter.validationfailed", CollectionBuilder.newBuilder(projectOrFilterId, error).asList()));
            }
        }
        else
        {
            validationErrors.add(new ValidationError("projectOrFilterId", "gadget.common.required.query"));
        }
    }

    void validateDaysPrevious(String daysPrevious, Collection<ValidationError> validationErrors)
    {
        if (StringUtils.isBlank(daysPrevious) || !StringUtils.isNumeric(daysPrevious))
            validationErrors.add(new ValidationError("daysprevious", "gadget.common.days.nan"));
        else if (Long.parseLong(daysPrevious) < 0)
            validationErrors.add(new ValidationError("daysprevious", "gadget.common.negative.days"));
        else
            validateDaysAgainstPeriod("daysprevious", ChartFactory.PeriodName.daily, Integer.parseInt(daysPrevious), validationErrors);

    }

    /**
     * Ensures that the number of days specified does not exceed the upper limit for the given period, as defined in
     * JIRA's properties.
     *
     * @param fieldName the name of the field
     * @param period the period
     * @param days the number of days
     * @param errors the errors to return
     */
    private void validateDaysAgainstPeriod(String fieldName, ChartFactory.PeriodName period, int days, Collection<ValidationError> errors)
    {
        final String maxDaysPropertyKey = MAX_DAYS_AP_PREFIX + period.toString();
        final Integer limitForPeriod = Integer.valueOf(StringUtils.defaultString(applicationProperties.getDefaultBackedString(maxDaysPropertyKey), "300"));

        if (limitForPeriod < days)
        {
            errors.add(new ValidationError(fieldName, "gadget.common.days.overlimit.for.period", Arrays.asList(limitForPeriod.toString(), period.toString())));
        }
    }

    String getProjectNameOrFilterTitle(String projectOrFilterId)
    {
        if (projectOrFilterId.startsWith("project-"))
        {
            Project aProject = projectManager.getProjectObj(Long.parseLong(projectOrFilterId.substring("project-".length())));
            return null == aProject ? null : aProject.getName();
        }
        else if (projectOrFilterId.startsWith("filter-"))
        {
            SearchRequest searchRequest = searchRequestService.getFilter(
                    getJiraServiceContext(),
                    Long.parseLong(projectOrFilterId.substring("filter-".length()))
            );

            return null == searchRequest ? null : searchRequest.getName();
        }

        return "gadget.common.anonymous.filter";
    }

// Setter/getter methods need not to be tested.
///CLOVER:OFF
    protected JiraServiceContextImpl getJiraServiceContext()
    {
        return new JiraServiceContextImpl(jiraAuthenticationContext.getUser());
    }
///CLOVER:ON

    protected String getFilterUrl(final Map<String, Object> params)
    {
        if (params.containsKey("project"))
        {
            return "/browse/" + ((Project) params.get("project")).getKey();
        }
        else if (params.containsKey("searchRequest"))
        {
            final SearchRequest request = (SearchRequest) params.get("searchRequest");
            if (request != null && request.isLoaded())
            {
                return "/secure/IssueNavigator.jspa?mode=hide&requestId=" + request.getId();
            }
            else
            {
                return "/secure/IssueNavigator.jspa?reset=true&mode=hide" + searchService.getQueryString(jiraAuthenticationContext.getUser().getDirectoryUser(), (request == null) ? new QueryImpl() : request.getQuery());
            }
        }
        else
        {
            return "";
        }
    }

    private Response createErrorMessagesResponse(Set<String> errorMessages, int statusCode)
    {
        return Response.status(statusCode).entity(
                ErrorCollection.Builder.newBuilder(errorMessages).build()
        ).cacheControl(CacheControl.NO_CACHE).build();
    }

    Response createServiceUnavailableErrorMessagesResponse(Set<String> errorMessages)
    {
        return createErrorMessagesResponse(errorMessages, HttpStatus.SC_SERVICE_UNAVAILABLE);
    }

    Response createServiceUnavailableErrorMessageResponse(String errorMessage)
    {
        return createServiceUnavailableErrorMessagesResponse(new LinkedHashSet<String>(Arrays.asList(errorMessage)));
    }

    Response createNotFoundErrorMessageResponse(Set<String> errorMessages)
    {
        return createErrorMessagesResponse(errorMessages, HttpStatus.SC_NOT_FOUND);
    }

    Response createNotFoundErrorMessageResponse(String errorMessage)
    {
        return createNotFoundErrorMessageResponse(new LinkedHashSet<String>(Arrays.asList(errorMessage)));
    }

}