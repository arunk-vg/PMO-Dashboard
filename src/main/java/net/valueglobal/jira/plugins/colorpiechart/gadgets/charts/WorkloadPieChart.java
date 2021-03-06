package net.valueglobal.jira.plugins.colorpiechart.gadgets.charts;

import com.atlassian.crowd.embedded.api.User;
//import com.atlassian.gadgets.dashboard.Color;
import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.bc.issue.worklog.TimeTrackingConfiguration;
import com.atlassian.jira.charts.Chart;
import com.atlassian.jira.charts.PieSegmentWrapper;
import com.atlassian.jira.charts.jfreechart.ChartHelper;
import com.atlassian.jira.charts.jfreechart.PieChartGenerator;
import com.atlassian.jira.charts.jfreechart.util.ChartUtil;
import com.atlassian.jira.charts.jfreechart.util.PieDatasetUtil;
import com.atlassian.jira.charts.util.ChartUtils;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.index.IssueIndexManager;
import com.atlassian.jira.issue.search.ReaderCache;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchProvider;
import com.atlassian.jira.issue.search.SearchRequest;
import com.atlassian.jira.issue.statistics.FilterStatisticsValuesGenerator;
import com.atlassian.jira.issue.statistics.StatisticsMapper;
import com.atlassian.jira.issue.statistics.TimeTrackingStatisticsMapper;
import com.atlassian.jira.issue.statistics.TwoDimensionalStatsMap;
import com.atlassian.jira.issue.statistics.util.TwoDimensionalTermHitCollector;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.util.I18nHelper;
import com.atlassian.jira.util.JiraDurationUtils;
import com.atlassian.jira.util.velocity.VelocityRequestContext;
import com.atlassian.jira.util.velocity.VelocityRequestContextFactory;
import com.atlassian.jira.web.FieldVisibilityManager;
import com.atlassian.jira.web.bean.StatisticAccessorBean;
import com.atlassian.query.QueryImpl;

import org.apache.log4j.Logger;
import org.apache.lucene.search.Collector;
import org.jfree.chart.labels.PieToolTipGenerator;
import org.jfree.chart.labels.StandardPieSectionLabelGenerator;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.urls.CategoryURLGenerator;
import org.jfree.chart.urls.PieURLGenerator;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DefaultPieDataset;
import org.jfree.data.general.PieDataset;


import java.awt.Color;
import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


public class WorkloadPieChart implements ChartParamKeys
{
    private static final Logger LOG = Logger.getLogger(Logger.class);

    public static final String KEY_TOTAL_WORK_HOURS = "numIssues";

    private final CustomFieldManager customFieldManager;
    private final ConstantsManager constantsManager;

    private final IssueIndexManager issueIndexManager;

    private final SearchProvider searchProvider;

    private final SearchService searchService;

    private final ApplicationProperties applicationProperties;

    private final FieldVisibilityManager fieldVisibilityManager;
    private final ReaderCache readerCache;
    private final TimeTrackingConfiguration timeTrackingConfiguration;
    private final VelocityRequestContextFactory velocityRequestContextFactory;

    public WorkloadPieChart(CustomFieldManager customFieldManager, ConstantsManager constantsManager,
            IssueIndexManager issueIndexManager, SearchProvider searchProvider, SearchService searchService,
            ApplicationProperties applicationProperties, FieldVisibilityManager fieldVisibilityManager,
            ReaderCache readerCache, TimeTrackingConfiguration timeTrackingConfiguration,
            VelocityRequestContextFactory velocityRequestContextFactory)
    {
        this.customFieldManager = customFieldManager;
        this.constantsManager = constantsManager;
        this.issueIndexManager = issueIndexManager;
        this.searchProvider = searchProvider;
        this.searchService = searchService;
        this.applicationProperties = applicationProperties;
        this.fieldVisibilityManager = fieldVisibilityManager;
        this.readerCache = readerCache;
        this.timeTrackingConfiguration = timeTrackingConfiguration;
        this.velocityRequestContextFactory = velocityRequestContextFactory;
    }

    public Chart generateInline(JiraAuthenticationContext jiraAuthenticationContext, SearchRequest searchRequest,
            String statisticType, String issueTimeType, int width, int height)
            throws SearchException, IOException
    {
        return generateInternal(jiraAuthenticationContext, searchRequest, statisticType, issueTimeType, width, height, true);
    }

    public Chart generate(JiraAuthenticationContext jiraAuthenticationContext, SearchRequest searchRequest,
            String statisticType, String issueTimeType, int width, int height)
            throws SearchException, IOException
    {
        return generateInternal(jiraAuthenticationContext, searchRequest, statisticType, issueTimeType, width, height, false);
    }

    private Chart generateInternal(JiraAuthenticationContext jiraAuthenticationContext, SearchRequest searchRequest,
            String statisticType, String issueTimeType, int width, int height, boolean inline)
            throws SearchException, IOException
    {
        final JiraDurationUtils jiraDurationUtils = getJiraDurationUtils();
        final SearchRequest searchRequestCopy = getSearchRequest(searchRequest);
        final User user = jiraAuthenticationContext.getLoggedInUser();
        StatisticAccessorBean statBean = getStatisticAccessorBean(searchRequestCopy, user);

        StatisticsMapper yAxisStatsMapper;
        if ("currentestimate".equals(issueTimeType))
        {
            yAxisStatsMapper = TimeTrackingStatisticsMapper.TIME_ESTIMATE_CURR;
        }
        else if ("originalestimate".equals(issueTimeType))
        {
            yAxisStatsMapper = TimeTrackingStatisticsMapper.TIME_ESTIMATE_ORIG;
        }
        else
        {
            yAxisStatsMapper = TimeTrackingStatisticsMapper.TIME_SPENT;
        }

        final StatisticsMapper xAxisStatsMapper = statBean.getMapper(statisticType);

        TwoDimensionalStatsMap twoDimensionalStatsMap = getTwoDimensionalStatistics(
                user, searchRequestCopy,
                xAxisStatsMapper,
                new StatisticsMapperDelegator(yAxisStatsMapper));

        I18nHelper i18nHelper = jiraAuthenticationContext.getI18nHelper();

        PieDataset dataset = calculateWorkloadDataset(twoDimensionalStatsMap, statisticType, i18nHelper);
        PieDataset sortedDataset = PieDatasetUtil.createSortedPieDataset(dataset);
        PieDataset consolidatedDataset = PieDatasetUtil.createConsolidatedSortedPieDataset(sortedDataset, i18nHelper.getText("portlet.workloadpie.other"), false, 0.02, 10);

        final long totalWorkload = calculateTotal(dataset);

        I18nHelper i18nBean = jiraAuthenticationContext.getI18nHelper();
        ChartHelper helper = getPieChartGenerator(consolidatedDataset, i18nBean).generateChart();
        ChartUtil.setDefaults(helper.getChart(), i18nBean);

        PiePlot piePlot = (PiePlot) helper.getChart().getPlot();
        Color[] colors = {Color.yellow,Color.getHSBColor(0.000f,0.745f,0.647f), Color.green.darker()};
        // Color[] colors = {Color.getHSBColor(50,100,50),Color.getHSBColor(360,88,68), Color.getHSBColor(76,89,54)}; 
        PieRenderer renderer = new PieRenderer(colors); 
        renderer.setColor(piePlot, consolidatedDataset);
        
        piePlot.setLabelGenerator(new StandardPieSectionLabelGenerator()
        {
            public String generateSectionLabel(PieDataset dataset, Comparable key)
            {
                Number value = dataset.getValue(key);
                String timeInDays = jiraDurationUtils.getShortFormattedDuration(value.longValue());
                return key + ": " + timeInDays;

            }
        });
        piePlot.setToolTipGenerator(new PieToolTipGenerator()
        {
            public String generateToolTip(PieDataset dataset, Comparable key)
            {
                int convertToSec = 1;
                Number number = dataset.getValue(key);
                return key + ": " + jiraDurationUtils.getShortFormattedDuration(convertToSec * (number.longValue())) + " (" + (100 * number.longValue() / totalWorkload) + "%)";
            }
        });


        final VelocityRequestContext velocityRequestContext = velocityRequestContextFactory.getJiraVelocityRequestContext();
        PieURLGenerator pieURLGenerator = new PieURLGenerator()
        {
            public String generateURL(PieDataset dataset, Comparable key, int section)
            {
                if (key instanceof PieSegmentWrapper)
                {
                    SearchRequest searchUrlSuffix = xAxisStatsMapper.getSearchUrlSuffix(((PieSegmentWrapper) key).getKey(), searchRequestCopy);
                    return velocityRequestContext.getCanonicalBaseUrl() + "/secure/IssueNavigator.jspa?reset=true" + searchService.getQueryString(user, (searchUrlSuffix == null) ? new QueryImpl() : searchUrlSuffix.getQuery());
                }
                else
                    return "";
            }
        };
        piePlot.setURLGenerator(pieURLGenerator);

        if (inline)
        {
            helper.generateInline(width, height);
        }
        else
        {
            helper.generate(width, height);
        }

        Map<String, Object> params = new HashMap<String, Object>();

        params.put("chart", helper.getLocation());
        params.put("chartDataset", consolidatedDataset);

        DefaultCategoryDataset completeDataset = new DefaultCategoryDataset();
        for (Iterator iterator = sortedDataset.getKeys().iterator(); iterator.hasNext();)
        {
            Comparable key = (Comparable) iterator.next();
            Number value = sortedDataset.getValue(key);
            // The explicit usage of the Integer wrapper is required so that the pie segment labels appear properly.
            completeDataset.addValue(new Integer(value.intValue() / 3600), i18nHelper.getText("core.dateutils.hours"), key);
            completeDataset.addValue((100 * value.intValue() / totalWorkload), "%", key);
        }
        params.put(KEY_COMPLETE_DATASET, completeDataset);
        params.put(KEY_COMPLETE_DATASET_URL_GENERATOR, new CategoryURLGenerator()
        {
            public String generateURL(CategoryDataset categoryDataset, int row, int col)
            {
                Comparable key = categoryDataset.getColumnKey(col);
                if (key instanceof PieSegmentWrapper)
                {
                    SearchRequest searchUrlSuffix = xAxisStatsMapper.getSearchUrlSuffix(((PieSegmentWrapper) key).getKey(), searchRequestCopy);
                    return velocityRequestContext.getCanonicalBaseUrl() + "/secure/IssueNavigator.jspa?reset=true" + searchService.getQueryString(user, (searchUrlSuffix == null) ? new QueryImpl() : searchUrlSuffix.getQuery());
                }

                return null;
            }
        });
        params.put(KEY_TOTAL_WORK_HOURS, totalWorkload);
        params.put("imagemapHtml", helper.getImageMap());
        params.put("imagemapName", helper.getImageMapName());

        if (inline)
        {
            String base64Image = ComponentAccessor.getComponent(ChartUtils.class).renderBase64Chart(helper.getImage(), "PMO Dashboard Pie Chart");
            params.put("base64Image", base64Image);
        }
        return new Chart(helper.getLocation(), helper.getImageMap(), helper.getImageMapName(), params);
    }

    
    public static class PieRenderer 
    { 
        private Color[] color; 
        
        public PieRenderer(Color[] color) 
        { 
            this.color = color; 
        }        
        
        public void setColor(PiePlot plot, PieDataset consolidatedDataset) 
        { 
            List <Comparable> keys = consolidatedDataset.getKeys(); 
            int aInt;
            Object [] test = keys.toArray();
            
            //System.out.println("Test :" + test[0]);
            System.out.println("Test length :" + test.length);

            
          // if(test.equals(consolidatedDataset.getValue(0)))
            //System.out.println("keys index of " +keys.toString());
            for (int i = 0; i < test.length; i++){ 
            	String x = test[i].toString();
            	System.out.println(x);
            	if(x.contains("Yellow"))
            	{
        			System.out.println("Sucesssssssssssssssssssssss");
                    plot.setSectionPaint(keys.get(i),this.color[0]);
                }
            	else if(x.contains("Red"))
            	{
                    plot.setSectionPaint(keys.get(i), this.color[1]);
            	}
            	else if(x.contains("Green"))
            	{
                    plot.setSectionPaint(keys.get(i), this.color[2]);

            	}
                /*
            	if(test[0].equals("Yellow"))
    			{			System.out.println("Sucesssssssssssssssssssssss");
                    plot.setSectionPaint(keys.get(i), this.color[0]); 
    			}
    			else if(test[1] == "Red"){
                plot.setSectionPaint(keys.get(i), this.color[1]);
    			}
    			else if(test[2] == "Green")
    			{
                    plot.setSectionPaint(keys.get(i), this.color[2]);

    			}*/
				
			}
            
            
            
            /*for (int i = 0; i < test.length; i++) 
            { 
            //	System.out.println("Color Renderer KEYS :" +keys);
            //	System.out.println("Color Length KEYS :" +this.color.length);

                aInt = i % this.color.length; 
            	System.out.println("Color Renderer aINT : "+aInt);
            	System.out.println("Test dot equals : "+test.equals("Yellow"));
				if(test[i].equals("Yellow"))
				{			System.out.println("Sucesssssssssssssssssssssss");
	                plot.setSectionPaint(keys.get(i), this.color[0]); 
				}
				else if(test[1] == "Red"){
                plot.setSectionPaint(keys.get(i), this.color[1]);
				}
				else if(test[2] == "Green")
				{
	                plot.setSectionPaint(keys.get(i), this.color[2]);

				}
            } */
        } 
    } 
// Overwrite method need not be calculated by Clover
///CLOVER:OFF
    protected PieChartGenerator getPieChartGenerator(
            PieDataset consolidatedDataset, I18nHelper i18nBean)
    {
        return new PieChartGenerator(consolidatedDataset, i18nBean);
    }

    protected StatisticAccessorBean getStatisticAccessorBean(
            final SearchRequest searchRequestCopy, final User user)
    {
        return new StatisticAccessorBean(user, searchRequestCopy);
    }

    protected SearchRequest getSearchRequest(SearchRequest searchRequest)
    {
        return new SearchRequest(searchRequest);
    }

    protected JiraDurationUtils getJiraDurationUtils()
    {
        return ComponentAccessor.getComponentOfType(JiraDurationUtils.class);
    }
///CLOVER:ON

    private TwoDimensionalStatsMap getTwoDimensionalStatistics(User user, SearchRequest searchRequest, StatisticsMapper xAxis, StatisticsMapper yAxis)
            throws SearchException
    {
        TwoDimensionalStatsMap statisticsMap2d = getTwoDimensionalStatsMap(xAxis, yAxis);
        Collector hitCollector = getTwoDimensionalTermHitCollector(statisticsMap2d);
        searchProvider.search(searchRequest.getQuery(), user, hitCollector);

        return statisticsMap2d;
    }

    protected TwoDimensionalTermHitCollector getTwoDimensionalTermHitCollector(
            TwoDimensionalStatsMap statisticsMap2d)
    {
        return new TwoDimensionalTermHitCollector(statisticsMap2d, issueIndexManager.getIssueSearcher().getIndexReader(), fieldVisibilityManager, readerCache);
    }

    protected TwoDimensionalStatsMap getTwoDimensionalStatsMap(
            StatisticsMapper xAxis, StatisticsMapper yAxis)
    {
        return new TwoDimensionalStatsMap(xAxis, yAxis);
    }

    private PieDataset calculateWorkloadDataset(
            TwoDimensionalStatsMap statsMap,
            String statisticType,
            I18nHelper i18nHelper)
    {
        DefaultPieDataset dataset = new DefaultPieDataset();

        // weigh each statistic type with time factor - 2D matrix multiply
        Collection statisticsColl = statsMap.getXAxis();
        Collection timeSpentColl = statsMap.getYAxis();

        for (Iterator iteratorC = statisticsColl.iterator(); iteratorC.hasNext();)
        {
            Object entry = iteratorC.next();
            if (entry != null || statisticType.equals(FilterStatisticsValuesGenerator.ASSIGNEES))
            {  // we only count issues where components have been specified
               // JCHART-403 - Including unassigned issues into the chart

                int totalTime = 0;
                for (Iterator iteratorT = timeSpentColl.iterator(); iteratorT.hasNext();)
                {
                    Object time = iteratorT.next();

                    int num = statsMap.getCoordinate(entry, time);

                    if (time != null)
                        totalTime += num * ((Long) time);
                }

                PieSegmentWrapper psw = new PieSegmentWrapper(entry, i18nHelper, statisticType, constantsManager, customFieldManager);
                dataset.setValue(psw, totalTime);
            }
        }

        return dataset;
    }

    private long calculateTotal(PieDataset dataset)
    {
        long total = 0;
        for (Iterator i = dataset.getKeys().iterator(); i.hasNext();)
        {
            Comparable key = (Comparable) i.next();
            Number n = dataset.getValue(key);
            total += n.longValue();
        }
        return total;
    }

    private class PieSegmentWrapperHolder
{
    private PieSegmentWrapper pieSegmentWrapper;

    private long totalTime;

    private PieSegmentWrapperHolder(PieSegmentWrapper pieSegmentWrapper)
    {
        this.pieSegmentWrapper = pieSegmentWrapper;
        this.totalTime = totalTime;
    }
}

    private static class StatisticsMapperDelegator implements StatisticsMapper
    {
        private final StatisticsMapper statisticsMapper;

        private StatisticsMapperDelegator(StatisticsMapper<Long> statisticsMapper)
        {
            this.statisticsMapper = statisticsMapper;
        }

        public boolean isValidValue(Object o)
        {
            return statisticsMapper.isValidValue(o);
        }

        public boolean isFieldAlwaysPartOfAnIssue()
        {
            return statisticsMapper.isFieldAlwaysPartOfAnIssue();
        }

        public SearchRequest getSearchUrlSuffix(Object o, SearchRequest searchRequest)
        {
            return statisticsMapper.getSearchUrlSuffix(o, searchRequest);
        }

        public String getDocumentConstant()
        {
            return statisticsMapper.getDocumentConstant();
        }

        public Object getValueFromLuceneField(String s)
        {
            return statisticsMapper.getValueFromLuceneField(s);
        }

        public Comparator getComparator()
        {
            return new Comparator<Long>()
            {
                public int compare(Long left, Long right)
                {
                    if (left == right)
                        return 0;

                    if (null == left)
                        return -1;

                    if (null == right)
                        return 1;

                    return left.compareTo(right);
                }
            };
        }

        @Override
        public boolean equals(Object o)
        {
            return statisticsMapper.equals(o);
        }

        @Override
        public int hashCode()
        {
            return statisticsMapper.hashCode();
        }
    }
}