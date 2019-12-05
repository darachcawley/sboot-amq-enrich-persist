/*
 * Copyright 2016 Red Hat, Inc.
 * <p>
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */
package com.redhat.gpte.training.springboot;

import javax.ws.rs.HttpMethod;

import org.apache.activemq.jms.pool.PooledConnectionFactory;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.amqp.AMQPComponent;
import org.apache.camel.component.http4.HttpOperationFailedException;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.component.servlet.CamelHttpTransportServlet;
import org.apache.camel.model.dataformat.JacksonXMLDataFormat;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.dataformat.SoapJaxbDataFormat;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.fuse.usecase.AccountAggregator;
import org.fuse.usecase.ProcessorBean;
import org.globex.Account;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportResource;


@SpringBootApplication
// load regular Spring XML file from the classpath that contains the Camel XML DSL
@ImportResource({"classpath:spring/camel-context.xml"})
public class Application extends RouteBuilder{

    /**
     * A main method to start this application.
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
    
    @Override
    public void configure () throws Exception {
    	
	  onException()
	  	.log(LoggingLevel.INFO, "org.fuse.usecase", "onException triggered ${exception}")
	    .handled(true)
	    ;

	  // ENRICH - using multiple aggregator pattern  
	  // i.e. using services to perform different tasks e.g. convert NA=>North America
	  from("amqp:queue:accountQueue")
	  	  // state that we are not expected to return anything
	  	  .setExchangePattern(ExchangePattern.InOnly)
	      .log(LoggingLevel.INFO, "org.fuse.usecase", "account found on queue")
	      // unmarshal the json into an Account object
	      .unmarshal().json(JsonLibrary.Jackson, Account.class)
	      .log(LoggingLevel.INFO, "org.fuse.usecase", "account object unmarshalled")
	      // perform a multicast broadcast out to the two services, 
	      // in parallel and then send the two results to the Aggregator 
	      .multicast(new AccountAggregator()).parallelProcessing()
	      	// NOTE: must have a log entry as the first parameter
	      	.log(LoggingLevel.INFO, "org.fuse.usecase", "account object aggregated")
	      	// send in the endpoints to call as part of the multicast broadcast
	      	.to("direct:callRestEndpoint", "direct:callWSEndpoint")
	      	// end the multicast event
	      	.end()	      
	      // send the resulting, aggregated Account object to an internal route to be processed next
	      .to("direct:insert-account")	      
	  ;
	  
	  // REST call using http4 component
	  from("direct:callRestEndpoint")
	  	  .log(LoggingLevel.INFO, "org.fuse.usecase", "callRestEndpoint")
	  	  .setHeader(Exchange.HTTP_METHOD).constant(HttpMethod.POST)
	  	  .setHeader("ContentType").constant("application/json")
	  	  .setHeader("Accept").constant("application/json")
	  	  .setHeader("CamelHttpMethod").constant("POST")
	  	  .setHeader("CamelCxfRsUsingHttpAPI").constant("True")
	  	  // need to overwrite the URI here, otherwise it uses the original request that went onto the queue
	  	  .setHeader(Exchange.HTTP_URI, constant("http4://{{rest.host}}:{{rest.port}}{{rest.endpoint}}"))
	  	  .log(LoggingLevel.INFO, "org.fuse.usecase", "sending to rest: {{rest.host}}:{{rest.port}}")
	  	  // marshal the Account object into JSON to be sent to the rest endpoint
	  	  .marshal().json(JsonLibrary.Jackson)
	  	  // using camel-http4 component to send the rest call
	  	  // use the environment variables set in /src/main/resources/application-dev.properties
	  	  .to("http4://{{rest.host}}:{{rest.port}}{{rest.endpoint}}")
	  	  // write out the body
	  	  .log("Response : ${body}")
	  	  ;
	  
	  // SOAP call using CXF component
	  from("direct:callWSEndpoint")
		  .log(LoggingLevel.INFO, "org.fuse.usecase", "callWSEndpoint")
	  	  .setHeader(Exchange.HTTP_METHOD).constant(HttpMethod.POST)
	  	  .setHeader("ContentType").constant("application/xml")
	  	  .setHeader("CamelHttpMethod").constant("POST")
	  	  .setHeader(Exchange.HTTP_URI, constant("cxf://{{soap.host}}:{{soap.port}}{{soap.endpoint}}"))
	  	  .log(LoggingLevel.INFO, "org.fuse.usecase", "sending to soap: {{soap.host}}:{{soap.port}}")
	  	  // use the cxf component (JAX-RS wrapper) to marshal the Account object into a soap message,
	  	  // ensure it conforms to a wsdl, wrap it into a soap message and send the WS call.
	  	  // this is using a bean described in the /src/main/resources/spring/camel-context.xml
	  	  .to("cxf:bean:customerWebService")
	  	  .log("Response : ${body}");
		  ;
		  
	  from("direct:insert-account")
	  	  .log(LoggingLevel.INFO, "org.fuse.usecase", "insert-account ${body}")
	  	  // call a bean, specifying the method - this will return an HashMap of key/value pairs,
	  	  // used for insertion into the DB 
	  	  .bean(new ProcessorBean(), "defineNamedParameters")
	  	  .log(LoggingLevel.INFO, "org.fuse.usecase", "defineNamedParameters processed ${body}")
	  	  // Insert the HashMap (new Account data) into the DB
	  	  // using the sql component, add your SQL, using ':#' to reference field values
	  	  .to("sql:INSERT INTO USECASE.T_ACCOUNT(CLIENT_ID,SALES_CONTACT,COMPANY_NAME,COMPANY_GEO,"
	  	  		+ "COMPANY_ACTIVE,CONTACT_FIRST_NAME,CONTACT_LAST_NAME,CONTACT_ADDRESS,"
	  	  		+ "CONTACT_CITY,CONTACT_STATE,CONTACT_ZIP,CONTACT_PHONE,CREATION_DATE,CREATION_USER)"
	  	  		+ "VALUES(:#CLIENT_ID,:#SALES_CONTACT,:#COMPANY_NAME,:#COMPANY_GEO,:#COMPANY_ACTIVE,"
	  	  		+ ":#CONTACT_FIRST_NAME,:#CONTACT_LAST_NAME,:#CONTACT_ADDRESS,:#CONTACT_CITY,"
	  	  		+ ":#CONTACT_STATE,:#CONTACT_ZIP,:#CONTACT_PHONE,:#CREATION_DATE,:#CREATION_USER);")
	  	  .log(LoggingLevel.INFO, "org.fuse.usecase", "inserted ${body}")
	  	  ;
	  
    }
    
    @Bean(name = "amqp-component")
    AMQPComponent amqpComponent(AMQPConfiguration config) {
        JmsConnectionFactory qpid = new JmsConnectionFactory(config.getUsername(), config.getPassword(), "amqp://"+ config.getHost() + ":" + config.getPort());
        //qpid.setTopicPrefix("topic://");

        PooledConnectionFactory factory = new PooledConnectionFactory();
        factory.setConnectionFactory(qpid);

        return new AMQPComponent(factory);
    }

}
