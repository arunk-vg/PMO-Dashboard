package net.valueglobal.jira.plugins.colorpiechart.listener;

import java.sql.Date;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.ModifiedValue;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.customfields.option.Options;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.index.IssueIndexManager;
import com.atlassian.jira.issue.link.IssueLink;
import com.atlassian.jira.issue.link.IssueLinkManager;
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder;
import com.atlassian.jira.plugin.jql.function.LinkedIssuesFunction;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.rest.v2.issue.Link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Simple JIRA listener using the atlassian-event library and demonstrating
 * plugin lifecycle integration.
 */
public class CustomFieldUpdaterListener implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CustomFieldUpdaterListener.class);

    private final EventPublisher eventPublisher;

    /**
     * Constructor.
     * @param eventPublisher injected {@code EventPublisher} implementation.
     */
    public CustomFieldUpdaterListener(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Called when the plugin has been enabled.
     * @throws Exception
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        // register ourselves with the EventPublisher
        eventPublisher.register(this);
    }

    /**
     * Called when the plugin is being disabled or removed.
     * @throws Exception
     */
    @Override
    public void destroy() throws Exception {
        // unregister ourselves with the EventPublisher
        eventPublisher.unregister(this);
    }

    
    
    public Timestamp convertDateToTimestamp(java.util.Date date){
    	
    	Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.clear(Calendar.MINUTE);
        calendar.clear(Calendar.SECOND);
        calendar.clear(Calendar.MILLISECOND);
        java.util.Date truncatedDate = calendar.getTime();
        java.sql.Timestamp currentTimestamp = new java.sql.Timestamp(truncatedDate.getTime());
        //System.out.println("Truncated Date variable : "+ currentTimestamp);
		return currentTimestamp;
    	
    }
    
    public int compareTimespentEstimated(long timespent,long estimated){
    	
    	long tenPercent = (long) (0.10 * estimated); 
    	System.out.println("Ten Percent : " +tenPercent);
    	if(timespent <= estimated)
    	{
    		System.out.println("Timespent Less Than Estimated = Green");
    		return 0;    	// Green
    	}
    	else if(timespent > estimated && timespent != tenPercent + estimated)
    	{
    		System.out.println("Timespent Greater Than Estimated and != 10% variance = Red");
    		return 1; // Red

    	}
    	else if(timespent == tenPercent + estimated)
    	{
    		System.out.println("Timespent == 10% variance = Yellow");
    		return 2; // Yellow
    	}
    	else
    		return -1;
    }
    
    public Timestamp add1DayToTimestamp(Timestamp dt)
    {   
		    Calendar c = Calendar.getInstance(); 
		    c.setTime(dt); 
		    c.add(Calendar.DATE, 1);
			java.sql.Timestamp new_date = new java.sql.Timestamp(c.getTime().getTime());
    		System.out.println("One day added to Due Date: " + new_date);
    		
    		/*java.sql.Timestamp ts = ...

    				Calendar cal = Calendar.getInstance();
    				cal.setTime(min_date);
    				cal.add(Calendar.DAY_OF_WEEK, 1);
    				java.sql.Timestamp new_date = new java.sql.Timestamp(cal.getTime().getTime());

    				System.out.println("new Cal="+new_date);*/
    		
    		
    		

		    return new_date;    
    }
    
    public int compareDueDateCurrentDate(Timestamp duedate,Timestamp currentdate)
    {
	
			Timestamp nextDay =  add1DayToTimestamp(duedate);
			
			if (duedate.after(currentdate) || duedate.equals(currentdate))
			{
	    		System.out.println(" Current date lesser than Due Date or Current Date equals duedate --Green");
				return 0; //Green
			}
			else if(duedate.before(currentdate) && !nextDay.equals(currentdate))
			{
	    		System.out.println(" Current date Greater than Due Date or Current Date equals duedate --Red");
				return 1; //Red
			}
			else if(nextDay.equals(currentdate))
			{
	    		System.out.println(" Current date is Equal to Next day 10% variance --Yellow");
				return 2; // Yellow
			}
			else
			{			
				return -1;
			}
    
    }
    
    public void updateHealthField(Issue issue){
         Calendar calendar = Calendar.getInstance();
         java.util.Date date = calendar.getTime();       
         Timestamp currentTimestamp = convertDateToTimestamp(date);
         
      //System.out.println("CurrentTimestamp : "+ issue.getEstimate() + "estimated hours: "+issue.getTimeSpent());
         CustomField customFieldName = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectByName("Health");        
         OptionsManager optManager = ComponentAccessor.getOptionsManager();            
         List<Option> options = optManager.getOptions(customFieldName.getRelevantConfig(issue));//getAllOptions();// 
         System.out.println("Option List values : "+options);
         Option newOption = null;
         
         // Comparing DueDates
       //  int timeValue = issue.getDueDate().compareTo(currentTimestamp);
         
         
         int timeValue = compareDueDateCurrentDate(issue.getDueDate(),currentTimestamp);
         
         System.out.println("DueDateVSCurrentDate : "+timeValue);
         
         int comparedHours = -1;
         if(issue.getEstimate() != null || issue.getTimeSpent() != null){
         	System.out.println("Estimated : " +issue.getEstimate()+ issue.getOriginalEstimate() + " Timespent : " +issue.getTimeSpent());
             comparedHours = compareTimespentEstimated(issue.getTimeSpent(), issue.getEstimate());
             System.out.println(" Compared hours : "+comparedHours);

         }
         	if(timeValue == 0 && comparedHours == 0 )
              {	
         		 newOption = options.get(2);// Green // Check your option id ----0-Green 
         		 															   //1-Red 
         		 															   //2-Yellow
              }
         	 else if(timeValue == 0 && comparedHours == 1)
         	 {
         		 newOption = options.get(0); // Red // Check your option id
         	 }
         	 else if(timeValue == 0 && comparedHours == 2)
         	 {
         		 newOption = options.get(1); // Yellow Check your option id
         	 }
         	 else if(timeValue == 1 && comparedHours == 0)
         	 {
         		 newOption = options.get(0); // Red // Check your option id
         	 }
         	 else if(timeValue == 1 && comparedHours == 1)
         	 {
         		 newOption = options.get(0); // Red Check your option id
         	 }
         	 else if(timeValue == 1 && comparedHours == 2)
         	 {
         		 newOption = options.get(0); // Red Check your option id
         	 }
         	 else if(timeValue == 2 && comparedHours == 0)
         	 {
         		 newOption = options.get(1); // Red // Check your option id
         	 }
         	 else if(timeValue == 2 && comparedHours == 1)
         	 {
         		 newOption = options.get(0); // Red Check your option id
         	 }
         	 else if(timeValue == 2 && comparedHours == 2)
         	 {
         		 newOption = options.get(1); // Yellow Check your option id
         	 }
         	
         System.out.println("newOption val being set to Health value :" + newOption);             
         ModifiedValue mVal = new ModifiedValue(issue.getCustomFieldValue(customFieldName), newOption );            
         System.out.println(mVal);
         customFieldName.updateValue(null, issue, mVal, new DefaultIssueChangeHolder());  
    }
    
    public void updateHealthFieldOnCreate(Issue issue){
        Calendar calendar = Calendar.getInstance();
        java.util.Date date = calendar.getTime();       
        Timestamp currentTimestamp = convertDateToTimestamp(date);
        
     //System.out.println("CurrentTimestamp : "+ issue.getEstimate() + "estimated hours: "+issue.getTimeSpent());
        CustomField customFieldName = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectByName("Health");        
        OptionsManager optManager = ComponentAccessor.getOptionsManager();            
        List<Option> options = optManager.getOptions(customFieldName.getRelevantConfig(issue));//getAllOptions();// 
        System.out.println("Option List values : "+options);
        Option newOption = null;
               
        int timeValue = compareDueDateCurrentDate(issue.getDueDate(),currentTimestamp);
        
        System.out.println("DueDateVSCurrentDate : "+timeValue);
        
        /*int comparedHours = -1;
        if(issue.getEstimate() != null || issue.getTimeSpent() != null){
        	System.out.println("Estimated : " +issue.getEstimate()+ issue.getOriginalEstimate() + " Timespent : " +issue.getTimeSpent());
            comparedHours = compareTimespentEstimated(issue.getTimeSpent(), issue.getEstimate());
            System.out.println(" Compared hours : "+comparedHours);

        }*/
        	if(timeValue == 0)
             {	
        		 newOption = options.get(2);// Green // Check your option id ----0-Green 
        		 															   //1-Red 
        		 															   //2-Yellow
             }
        	
        	 else if(timeValue == 1)
        	 {
        		 newOption = options.get(0); // Red // Check your option id
        	 }
        	
        	 else if(timeValue == 2 )
        	 {
        		 newOption = options.get(1); // Yellow // Check your option id
        	 }
        	
        	
        System.out.println("newOption val being set to Health value :" + newOption);             
        ModifiedValue mVal = new ModifiedValue(issue.getCustomFieldValue(customFieldName), newOption );            
        System.out.println(mVal);
        customFieldName.updateValue(null, issue, mVal, new DefaultIssueChangeHolder());  
   }

    /**
     * Receives any {@code IssueEvent}s sent by JIRA.
     * @param issueEvent the IssueEvent passed to us
     */
    @EventListener
    public void onIssueEvent(IssueEvent issueEvent) {
        Long eventTypeId = issueEvent.getEventTypeId();
        Issue issue = issueEvent.getIssue();

        User currentUserObj = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
     
  IssueManager issueManager = ComponentAccessor.getIssueManager();
  IssueLinkManager	linkMgr = (IssueLinkManager) ComponentAccessor.getIssueLinkManager().getLinkCollection(issue, currentUserObj);
 /* ProjectManager     projectMgr = ComponentAccessor.getProjectManager();
  IssueIndexManager issueIndexMgr = ComponentAccessor.getIssueIndexManager();*/
  log.debug ("Current issue ID: " + issue.getId());
  log.debug ("Looking for Links...");
  System.out.println("Issue Link Manager : " +linkMgr);
  /*for each(IssueLinkManager	linkMgr : String t)
  {
	  
  }*/
  
  
        
      /*//Iterate through the dev issue's links
        for (IssueLink link : linkMgr.getOutwardLinks(issue.getId())) {
        // issueLinkTypeName = link.issueLinkType.name;
         LinkedIssue linkedIssue =    link.getDestinationObject();
            String linkProjectName = linkedIssue.getProjectObject().getName();
             linkedIssueKey = linkedIssue.getKey();
             linkedIssueID = linkedIssue.getId();
             linkedIssueStatus = linkedIssue.getStatusObject().getName();*/
        
   //  IssueLinkManager links; //.getLinkCollection (issue, user, true)
        
    // links.getLinkCollection(issue,);
        if (eventTypeId.equals(EventType.ISSUE_CREATED_ID)) {
        	updateHealthFieldOnCreate(issue);        	
        } 
        else 
        {
        	if(issue.getOriginalEstimate()!= null && issue.getTimeSpent()!= null)
        	{
        		updateHealthField(issue);
        	}
        	else
        	{
            	updateHealthFieldOnCreate(issue);        	
        	}
        }
    }
    
   /* private MutableIssue getMutableIssue(Issue issue) {
        MutableIssue mutableIssue;
        if (issue instanceof MutableIssue)   {
            mutableIssue = (MutableIssue)issue;
        } else {
            mutableIssue = ComponentAccessor.getIssueManager().getIssueObject(issue.getKey());
        }
        return mutableIssue;
    }*/
}