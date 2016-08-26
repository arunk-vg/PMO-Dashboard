package net.valueglobal.jira.plugins.colorpiechart.gadgets;

import com.atlassian.jira.bc.filter.SearchRequestService;
import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.bc.issue.worklog.TimeTrackingConfiguration;
import com.atlassian.jira.charts.Chart;
import com.atlassian.jira.charts.util.ChartUtils;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.config.properties.APKeys;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.index.IssueIndexManager;
import com.atlassian.jira.issue.search.ReaderCache;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchProvider;
import com.atlassian.jira.issue.search.SearchRequest;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.rest.v1.model.errors.ErrorCollection;
import com.atlassian.jira.rest.v1.model.errors.ValidationError;
import com.atlassian.jira.rest.v1.util.CacheControl;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.util.JiraDurationUtils;
import com.atlassian.jira.util.velocity.VelocityRequestContextFactory;
import com.atlassian.jira.web.FieldVisibilityManager;
import com.atlassian.plugins.rest.common.security.AnonymousAllowed;

import net.valueglobal.jira.plugins.colorpiechart.gadgets.charts.WorkloadPieChart;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.lang.StringUtils;
import org.jfree.chart.urls.CategoryURLGenerator;
import org.jfree.data.category.CategoryDataset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import static com.atlassian.jira.issue.fields.FieldManager.CUSTOM_FIELD_PREFIX;
import static net.valueglobal.jira.plugins.colorpiechart.gadgets.charts.WorkloadPieChart.KEY_COMPLETE_DATASET;
import static net.valueglobal.jira.plugins.colorpiechart.gadgets.charts.WorkloadPieChart.KEY_COMPLETE_DATASET_URL_GENERATOR;
import static net.valueglobal.jira.plugins.colorpiechart.gadgets.charts.WorkloadPieChart.KEY_TOTAL_WORK_HOURS;

@Path("workloadpie")
@AnonymousAllowed
public class WorkloadPieChartResource extends ProjectOrFilterIdBasedChartResource
{
    private final CustomFieldManager customFieldManager;
    private final ConstantsManager constantsManager;
    private final IssueIndexManager issueIndexManager;
    private final SearchProvider searchProvider;
    private final FieldVisibilityManager fieldVisibilityManager;
    private final ReaderCache readerCache;
    private final TimeTrackingConfiguration timeTrackingConfiguration;

    public WorkloadPieChartResource(JiraAuthenticationContext jiraAuthenticationContext, ProjectManager projectManager,
            SearchRequestService searchRequestService, SearchService searchService, ChartUtils chartUtils,
            PermissionManager permissionManager, VelocityRequestContextFactory velocityRequestContextFactory,
            ApplicationProperties applicationProperties, CustomFieldManager customFieldManager,
            ConstantsManager constantsManager, IssueIndexManager issueIndexManager, SearchProvider searchProvider,
            FieldVisibilityManager fieldVisibilityManager, ReaderCache readerCache,
            TimeTrackingConfiguration timeTrackingConfiguration)
    {
        super(jiraAuthenticationContext, projectManager, searchRequestService, searchService, chartUtils, permissionManager, velocityRequestContextFactory, applicationProperties);
        this.customFieldManager = customFieldManager;
        this.constantsManager = constantsManager;
        this.issueIndexManager = issueIndexManager;
        this.searchProvider = searchProvider;
        this.fieldVisibilityManager = fieldVisibilityManager;
        this.readerCache = readerCache;
        this.timeTrackingConfiguration = timeTrackingConfiguration;
    }

    @GET
    @Path("generate")
    @Produces({MediaType.APPLICATION_JSON})
    public Response generate(
            @QueryParam("projectOrFilterId") String projectOrFilterId,
            @QueryParam("statistictype") String statisticType,
            @QueryParam("issuetimetype") String issueTimeType,
            @QueryParam("width") @DefaultValue("400") int width,
            @QueryParam("height") @DefaultValue("250") int height,
            @QueryParam("returnData") @DefaultValue("false") boolean returnData,
            @QueryParam (INLINE) @DefaultValue ("false") final boolean inline) throws SearchException, IOException
    {
        if (!applicationProperties.getOption(APKeys.JIRA_OPTION_TIMETRACKING))
            return createNotFoundErrorMessageResponse(jiraAuthenticationContext.getI18nHelper().getText("gadget.workloadpie.error.timetrackingdisabled"));

        Map<String, Object> params = new HashMap<String, Object>();

        SearchRequest searchRequest = getSearchRequest(projectOrFilterId, params);
        if (searchRequest == null)
        {
            return createServiceUnavailableErrorMessageResponse(jiraAuthenticationContext.getI18nHelper().getText("report.workloadpie.error.invalidfilterorproject"));
        }
        WorkloadPieChart chart = getWorkloadPieChart();

        // So that we don't crash in the navigator view. No and we can't use @DefaultValue because the
        // navigator sends it with an empty value
        statisticType = StringUtils.defaultIfEmpty(statisticType, "assignees");
        issueTimeType = StringUtils.defaultIfEmpty(issueTimeType, "timespent");

        String customFieldLabel = null;
        if (statisticType != null && statisticType.startsWith(CUSTOM_FIELD_PREFIX))
            customFieldLabel = customFieldManager.getCustomFieldObject(statisticType).getName();

        try
        {
            Chart theChart = inline ? chart.generateInline(
                    jiraAuthenticationContext,
                    searchRequest,
                    statisticType,
                    issueTimeType,
                    width,
                    height
            ) : chart.generate(
                    jiraAuthenticationContext,
                    searchRequest,
                    statisticType,
                    issueTimeType,
                    width,
                    height
            );
            params.putAll(theChart.getParameters());

            final JiraDurationUtils jiraDurationUtils = getJiraDurationUtils();

            WorkloadPieChartJson workloadPieChartJson = new WorkloadPieChartJson(
                    theChart.getLocation(),
                    theChart.getImageMap(),
                    theChart.getImageMapName(),
                    width,
                    height,
                    getProjectNameOrFilterTitle(projectOrFilterId),
                    getFilterUrl(params),
                    jiraDurationUtils.getShortFormattedDuration((Long) theChart.getParameters().get(KEY_TOTAL_WORK_HOURS)),
                    statisticType,
                    issueTimeType,
                    customFieldLabel,
                    theChart.getBase64Image());

            if (returnData)
            {
                workloadPieChartJson.data = getData(theChart.getParameters());
                workloadPieChartJson.timeSpent = jiraDurationUtils.getShortFormattedDuration((Long) theChart.getParameters().get(KEY_TOTAL_WORK_HOURS));
            }

            return Response.ok(workloadPieChartJson).cacheControl(CacheControl.NO_CACHE).build();
        }
        catch (ArithmeticException ex)
        {
            if (StringUtils.isEmpty(issueTimeType))
            {
                //Assume timespent, which is the default old behaviour
                issueTimeType = "timespent";
            }
            return createServiceUnavailableErrorMessageResponse(jiraAuthenticationContext.getI18nHelper().getText("report.workloadpie.arithmeticerror." + issueTimeType));
        }
    }

// Setter/gettter need not to be in Clover calculation
///CLOVER:OFF
    protected JiraDurationUtils getJiraDurationUtils()
    {
        return ComponentAccessor.getComponentOfType(JiraDurationUtils.class);
    }

    protected WorkloadPieChart getWorkloadPieChart()
    {
        return new WorkloadPieChart(customFieldManager, constantsManager, issueIndexManager, searchProvider,
                searchService, applicationProperties, fieldVisibilityManager, readerCache,
                timeTrackingConfiguration, velocityRequestContextFactory);
    }

    protected SearchRequest getSearchRequest(String projectOrFilterId,
            Map<String, Object> params)
    {
        SearchRequest oldSearchRequest = chartUtils.retrieveOrMakeSearchRequest(projectOrFilterId, params);

        return oldSearchRequest == null ? null : new SearchRequest(oldSearchRequest);
    }
///CLOVER:ON

    @GET
    @Path("config/validate")
    @Produces({MediaType.APPLICATION_JSON})
    public Response validate(
            @QueryParam("projectOrFilterId") String projectOrFilterId)
    {
        Collection<ValidationError> validationErrors = new ArrayList<ValidationError>();
        validateProjectOrFilterId(projectOrFilterId, validationErrors);

        if (validationErrors.isEmpty())
        {
            return Response.ok().cacheControl(CacheControl.NO_CACHE).build();
        }
        else
        {
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(ErrorCollection.Builder.newBuilder(validationErrors).build()).cacheControl(CacheControl.NO_CACHE).build();
        }
    }

    @XmlRootElement
    public static class WorkloadPieChartJson
    {
        @XmlElement
        private String location;

        @XmlElement
        private String imageMap;

        @XmlElement
        private String imageMapName;

        @XmlElement
        private int width;

        @XmlElement
        private int height;

        @XmlElement
        private String filterTitle;

        @XmlElement
        private String filterUrl;

        @XmlElement
        private List<WorkloadPieDataRow> data;

        @XmlElement
        private String timeSpent;

        @XmlElement
        private String statType;

        @XmlElement
        private String issueTimeType;

        @XmlElement
        private String customFieldLabel;

        @XmlElement
        private String base64Image;

// Empty methods doesn't need to be tested
///CLOVER:OFF
        public WorkloadPieChartJson()
        {
        }
///CLOVER:ON

        public WorkloadPieChartJson(String location, String imageMap, String imageMapName, int width, int height, String filterTitle, String filterUrl, String timeSpent, String statType, String issueTimeType, String customFieldLabel, String base64Image)
        {
            this.location = location;
            this.imageMap = imageMap;
            this.imageMapName = imageMapName;
            this.width = width;
            this.height = height;
            this.filterTitle = filterTitle;
            this.filterUrl = filterUrl;
            this.timeSpent = timeSpent;
            this.statType = statType;
            this.issueTimeType = issueTimeType;
            this.customFieldLabel = customFieldLabel;
            this.base64Image = base64Image;
        }
    }

    List<WorkloadPieDataRow> getData(Map<String, Object> chartParams)
    {
        final CategoryURLGenerator completeUrlGenerator = (CategoryURLGenerator) chartParams.get(KEY_COMPLETE_DATASET_URL_GENERATOR);
        final CategoryDataset completeDataset = (CategoryDataset) chartParams.get(KEY_COMPLETE_DATASET);

        return generateDataSet(completeDataset, completeUrlGenerator);
    }

    List<WorkloadPieDataRow> generateDataSet(CategoryDataset dataset, CategoryURLGenerator urlGenerator)
    {
        List<WorkloadPieDataRow> data = new ArrayList<WorkloadPieDataRow>();

        for (int col = 0; col < dataset.getColumnCount(); col++)
        {
            Comparable key = dataset.getColumnKey(col);
            int val = dataset.getValue(0, col).intValue();
            String url = urlGenerator.generateURL(dataset, 0, col);
            int percentage = dataset.getValue(1, col).intValue();
            data.add(new WorkloadPieDataRow(key.toString(), url, val, percentage));
        }

        return data;
    }

    @XmlRootElement
    public static class WorkloadPieDataRow
    {
        @XmlElement
        private String key;

        @XmlElement
        private int value;

        @XmlElement
        private String url;

        @XmlElement
        private int percentage;

// Empty methods doesn't need to be tested
///CLOVER:OFF
        @SuppressWarnings({"UnusedDeclaration", "unused"})
        public WorkloadPieDataRow()
        {
        }
///CLOVER:ON

        public WorkloadPieDataRow(final String key, final String url, final int value, final int percentage)
        {
            this.key = key;
            this.value = value;
            this.url = url;
            this.percentage = percentage;
        }
    }
}