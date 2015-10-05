/**
 * Mule Anypoint Template
 *
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 */

package org.mule.templates.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mule.MessageExchangePattern;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.processor.chain.InterceptingChainLifecycleWrapper;
import org.mule.processor.chain.SubflowInterceptingChainLifecycleWrapper;
import org.mule.streaming.ConsumerIterator;
import org.mule.templates.builders.ObjectBuilder;

import com.mulesoft.module.batch.BatchTestHelper;
import com.sforce.soap.partner.SaveResult;

/** 
 * The objective of this class is to validate the correct behavior of the flows for this Mule Template that make calls to external systems.
 */
public class BusinessLogicFromCustomToCaseIT extends AbstractTemplateTestCase {

	private static final Logger LOGGER = LogManager.getLogger(BusinessLogicFromCaseToCustomIT.class);
	private static final int TIMEOUT_MILLIS = 60;
	
	private static final String ANYPOINT_TEMPLATE_NAME = "sfdc2sfdc-case2custom-bidirectional-sync";
	private static final String A_INBOUND_FLOW_NAME = "triggerSyncFromAFlow";
	private static final String B_INBOUND_FLOW_NAME = "triggerSyncFromBFlow";
	
	Map<String, Object> caseA = null;
	Map<String, Object> caseB = null;
	
	private BatchTestHelper batchTestHelper;
	
	private InterceptingChainLifecycleWrapper queryCaseInAFlow;
	private SubflowInterceptingChainLifecycleWrapper createCaseInBFlow;
	private SubflowInterceptingChainLifecycleWrapper deleteCaseFromAFlow;
	private SubflowInterceptingChainLifecycleWrapper createCaseInAFlow;
	private SubflowInterceptingChainLifecycleWrapper deleteCaseFromBFlow;

	@BeforeClass
	public static void beforeTestClass() {
		System.setProperty("page.size", "1000");

		// Set polling frequency to 10 seconds
		System.setProperty("polling.frequency", "10000");

		// Set default water-mark expression to current time
		System.clearProperty("watermark.default.expression");
		DateTime now = new DateTime(DateTimeZone.UTC);
		DateTimeFormatter dateFormat = DateTimeFormat
				.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
		System.setProperty("watermark.default.expression",
				now.toString(dateFormat));
	}
	
	@Before
	public void setUp() throws Exception {
		stopAutomaticPollTriggering();
		getAndInitializeFlows();
		
		batchTestHelper = new BatchTestHelper(muleContext);
		
		// Create test Custom Case in instance B
		createCustomCaseB();
	}

	@After
	public void tearDown() throws Exception {
		deleteCases();
	}

	private void stopAutomaticPollTriggering() throws MuleException {
		stopFlowSchedulers(B_INBOUND_FLOW_NAME);
		stopFlowSchedulers(A_INBOUND_FLOW_NAME);
	}

	private void getAndInitializeFlows() throws InitialisationException {
		queryCaseInAFlow = getSubFlow("queryCaseInAFlow");
		queryCaseInAFlow.initialise();
		
		createCaseInBFlow = getSubFlow("createCaseInBFlow");
		createCaseInBFlow.initialise();
		
		deleteCaseFromAFlow = getSubFlow("deleteCaseFromAFlow");
		deleteCaseFromAFlow.initialise();

		deleteCaseFromBFlow = getSubFlow("deleteCaseFromBFlow");
		deleteCaseFromBFlow.initialise();
		
		createCaseInAFlow = getSubFlow("createCaseInAFlow");
		createCaseInAFlow.initialise();
	}

	private void executeWaitAndAssertBatchJob(String flowConstructName)
			throws Exception {

		// Execute synchronization
		runSchedulersOnce(flowConstructName);

		// Wait for the batch job execution to finish
		batchTestHelper.awaitJobTermination(TIMEOUT_MILLIS * 1000, 500);
		batchTestHelper.assertJobWasSuccessful();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void whenCreatingACustomObjectInBACaseIsCreatedInA() throws Exception {
		// Execution
		executeWaitAndAssertBatchJob(B_INBOUND_FLOW_NAME);
		
		// Get the data from A instance
		Map<String, Object> caseA = new HashMap<String, Object>();
		this.caseA = caseA;
		final Object caseIdInB = this.caseB.get("Id");
		caseA.put("ExtId__c", caseIdInB);
		MuleEvent event = queryCaseInAFlow.process(getTestEvent(caseA, MessageExchangePattern.REQUEST_RESPONSE));

		ConsumerIterator<Object> queryResult = (ConsumerIterator<Object>) event.getMessage().getPayload();
		Map<String, Object> customObject = queryResult.hasNext() ? (Map<String, Object>) queryResult.next() : null;  
		LOGGER.info("Query Case from A result " + customObject);

		// Assertions
		assertNotNull(customObject);
		
		caseA.put("Id", customObject.get("Id"));
		
		assertEquals("The Id is not the right one: ", caseIdInB, customObject.get("ExtId__c"));
		
		
		assertEquals("The Subject is not the right one: ", customObject.get("Subject"), caseB.get("Subject__c"));
		assertEquals("The Type is not the right one: ", "Case", customObject.get("type"));
	}


	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void createCustomCaseB() throws Exception {
		caseB = createCase__c();
		List casesB = new ArrayList();
		casesB.add(caseB);
		
		MuleEvent event = createCaseInBFlow.process(getTestEvent(casesB, MessageExchangePattern.REQUEST_RESPONSE));
		List<SaveResult> result = (List<SaveResult>) event.getMessage().getPayload();
		caseB.put("Id", result.get(0).getId());
		LOGGER.info("Save Case__c to B result " +result.get(0));
	}

	private void deleteCases() throws Exception {
		MuleEvent event = null;
		Object result = null;
		
		if (caseA != null) {
			List<Object> casesA = new ArrayList<Object>();			
			casesA.add(caseA.get("Id"));
			
			event = deleteCaseFromAFlow.process(getTestEvent(casesA, MessageExchangePattern.REQUEST_RESPONSE));
			result = event.getMessage().getPayload();
			LOGGER.info("Delete Case from A result: " + result);
		}

		if (caseB != null) {
			List<Object> casesB = new ArrayList<Object>();
			casesB.add(caseB.get("Id"));
			
			deleteCaseFromBFlow = getSubFlow("deleteCaseFromBFlow");
			event = deleteCaseFromBFlow.process(getTestEvent(casesB, MessageExchangePattern.REQUEST_RESPONSE));
			result = event.getMessage().getPayload();
			LOGGER.info("Delete Case from B result: " + result);
		}
	}

	private Map<String, Object> createCase__c() {
		String name = buildUniqueName();
		return ObjectBuilder.aCustomObject()
				.with("Subject__c", name)
				.with("Description__c", name)
				.with("Priority__c", "Low")
				.with("Status__c", "New")
				.with("Origin__c", "Phone")
				.with("Account__c", null)
				.with("Contact__c", null)
				.build();
	}

	private String buildUniqueName() {
		return ANYPOINT_TEMPLATE_NAME + "-" + System.currentTimeMillis() + "Case";
	}

}
