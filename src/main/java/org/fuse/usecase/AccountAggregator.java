package org.fuse.usecase;

import org.apache.camel.Exchange;
import org.apache.camel.processor.aggregate.AggregationStrategy;
import org.apache.cxf.message.MessageContentsList;
import org.globex.Account;
import org.globex.Company;
import org.globex.CorporateAccount;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Aggregator implementation which extract the id and salescontact
 * from CorporateAccount and update the Account
 */
public class AccountAggregator implements AggregationStrategy {

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {

    	System.out.println("aggregating!!!");
    	// the first endpoint being called by multicase will NOT have an oldExchange, 
    	// only newExchange i.e. the rest call is first
        if (oldExchange == null) {
        	System.out.println("oldExchange is null");
            return newExchange;
        }

        // catch any logging that might get called during the multicast
        // for some (yet unknown) reason, you need a log entry within the multicast call in the route,
        // otherwise it doesn't work - resulting in you needing this entry to catch log entries
        if(oldExchange.getIn().getBody() instanceof String) {
        	return newExchange;
        }

        // the second endpoint called is soap
        Account acc = oldExchange.getIn().getBody(Account.class);
        CorporateAccount ca = newExchange.getIn().getBody(CorporateAccount.class);

        acc.setSalesRepresentative(ca.getSalesContact());
        acc.setClientId(ca.getId());

        return oldExchange;
    }
    
}