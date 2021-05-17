package com.cav.currencyexchange.service.matching;


import java.time.LocalDateTime;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cav.currencyexchange.cache.OrdersCache;
import com.cav.currencyexchange.models.CurrencyOrder;
import com.cav.currencyexchange.models.Status;
import com.cav.currencyexchange.repository.CurrencyRepository;
import com.cav.currencyexchange.service.ServiceBase;

/**
 * Will match orders, only matches full orders at the moment
 * @author Tony
 *
 */

public class MatchingServiceMultiProcessingImpl extends ServiceBase implements MatchingService {
	
	
	static final Logger log = LoggerFactory.getLogger(MatchingServiceMultiProcessingImpl.class);
	String currencyKeyPair;
	String currencyA = null;
	String currencyB = null;
	ReentrantLock lock = new ReentrantLock();
	CurrencyRepository currencyRepository = null;


	
	@Override
	/**
	 * When you buy a currency pair from a forex broker, you buy the base currency 
	 * and sell the quote currency. Conversely, when you sell the currency pair, 
	 * you sell the base currency and receive the quote currency. 
	 * Currency pairs are quoted based on their bid (buy) and ask prices (sell)
	 * @param currencyKeyPair
	 */
	public void matchingServiceMultiProcessing(String currencyKeyPair, CurrencyRepository currencyRepository) {
		this.currencyKeyPair = currencyKeyPair;
		String[] fields = currencyKeyPair.split("/");
		currencyA = fields[0];
		currencyB = fields[1];
		LocalDateTime checkDateTime = LocalDateTime.now();
		CopyOnWriteArrayList<CurrencyOrder> buys = OrdersCache.buyOrders.get(currencyKeyPair);
		if(buys != null){
			buys.parallelStream().filter(buy ->hasFunds(buy.getPartnerId()+currencyA, buy.getAmount()) && buy.getExpirationDate().isAfter(checkDateTime) && buy.getStatus().equals(Status.UNMATCHED)).forEach(buy->matchLocked(buy, currencyKeyPair, currencyA, currencyB, lock, currencyRepository, checkDateTime));
			
		}	
	}

}
