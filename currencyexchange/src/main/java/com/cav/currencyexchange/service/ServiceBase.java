package com.cav.currencyexchange.service;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.cav.currencyexchange.cache.AccountCache;
import com.cav.currencyexchange.cache.OrdersCache;
import com.cav.currencyexchange.entities.Account;
import com.cav.currencyexchange.entities.Currency;
import com.cav.currencyexchange.models.CurrencyOrder;
import com.cav.currencyexchange.models.Status;
import com.cav.currencyexchange.repository.CurrencyRepository;
import com.cav.currencyexchangebroker.generated.Orders;



public abstract class ServiceBase {
	
	
    CurrencyRepository currencyRepository;

	protected static final String SELL ="Sell";
	protected static final String BUY ="Buy";
	
	private static final Logger log = LoggerFactory.getLogger(ServiceBase.class);
	
	
	protected Orders streamOrders(InputStream stream){
		 JAXBContext jaxbContext;
		 Orders orders = null;
		try {
			jaxbContext = JAXBContext.newInstance(Orders.class);
			Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
			orders = (Orders) jaxbUnmarshaller.unmarshal(stream);
		} catch (JAXBException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if(stream != null){
				try {
					stream.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		return orders;
			
	}
	
	protected String getOrdersXML(InputStream stream){
		StringBuilder sb = new StringBuilder();
		BufferedReader br = null;
		String xml = null;
		
		try {
			sb = new StringBuilder();
			br = new BufferedReader(new InputStreamReader(stream));
			String line;
			while(null != (line = br.readLine())){
				sb.append(line);
				sb.append("\n");
			}
			xml = sb.toString();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if(br != null){
				try {
					br.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if(stream != null){
				try {
					stream.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		return xml;
	}
	
	protected boolean hasFunds(String key, BigDecimal amount) { 
		BigDecimal checkAmount = AccountCache.accounts.get(key);
		if (checkAmount != null) {
			if (checkAmount.compareTo(amount) >= 0) {
				return true;
			}
		}
		log.info("************************ RUN OUT OF FUNDS ***********************************");
		return false;
	}
	
	/**
	 * I looked into using Money I found that it took longer than just using BigDecimal and 
	 * the precision was the same.
	 * @param amount
	 * @param rate
	 * @return
	 */
	protected BigDecimal getAmount(BigDecimal amount, BigDecimal rate) {
		return amount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
	}
	
	
	/**
	 *  When you buy a currency pair from a forex broker, you buy the base currency 
	 * and sell the quote currency. Conversely, when you sell the currency pair, 
	 * you sell the base currency and receive the quote currency. 
	 * Currency pairs are quoted based on their bid (buy) and ask prices (sell)
	 * @param buy
	 * @param currencyKeyPair
	 * @param currencyA
	 * @param currencyB
	 * @param lock
	 * @param currencyRepository
	 * @param checkDateTime
	 */
	protected void matchLocked(CurrencyOrder buy, String currencyKeyPair, String currencyA, String currencyB, ReentrantLock lock, CurrencyRepository currencyRepository, LocalDateTime checkDateTime ) {
		buy.setStatus(Status.MATCHED);
		CopyOnWriteArrayList<CurrencyOrder> sells = OrdersCache.sellOrders.get(currencyKeyPair);
		for(CurrencyOrder sell : sells){
			BigDecimal sellAmount = getAmount(buy.getAmount(), buy.getExchangeRate());
			if(hasFunds(sell.getPartnerId() +currencyB, sellAmount)  && sell.getExpirationDate().isAfter(checkDateTime) && sell.getStatus().equals(Status.UNMATCHED)){
					lock.lock();
					 //Potential race condition CurrencyOrder could be processed while lock is being acquired
					 if(sell.getStatus().equals(Status.UNMATCHED)) {
						if(matchFull(buy, sell, currencyA, currencyB, sellAmount, currencyRepository)) {
							log.info("Matched order for buy "+buy.toString());
							log.info("Matched order for buy "+sell.toString());
							lock.unlock();
							break;
						} 
					} 
					lock.unlock();
			} else {
				buy.setStatus(Status.UNMATCHED);
			}
		}
	}
	
	/**
	 * currencyKeyPair baseCurrency/quoteCurrency
	 * @param buy
	 * @param sell
	 * @param baseCurrency
	 * @param quoteCurrency
	 * @param buyAmount
	 * @param sellAmount
	 */
	protected boolean matchFull(CurrencyOrder buy, CurrencyOrder sell, String baseCurrency,  String quoteCurrency, BigDecimal sellAmount, CurrencyRepository currencyRepository) {
		sell.setStatus(Status.MATCHED);
		BigDecimal buyAmount = buy.getAmount();
		String partnerABaseCurrency = buy.getPartnerId() + baseCurrency;
		String partnerAQuoteCurrency = buy.getPartnerId() + quoteCurrency;
		String partnerBBaseCurrency = sell.getPartnerId() + baseCurrency;
		String partnerBQuoteCurrency = sell.getPartnerId() + quoteCurrency;
		
			// Needs to be ACID for buy and sell.
			synchronized(AccountCache.accounts) {
				//Need to check again, there is enough currency in both to carry out the transaction
				//Another thread may have completed.
				if(hasFunds(partnerABaseCurrency, buyAmount) && hasFunds(partnerBQuoteCurrency, sellAmount)) {
					
					BigDecimal partnerAbuyAmount = null;
					BigDecimal partnerASellAmount = null;
					BigDecimal partnerBbuyAmount= null;
					BigDecimal partnerBSellAmount= null;
					try {
					//Partner A Buy BaseCurrency for QuoteCurrency
					Account buyAccount = new Account(buy.getPartnerId(), buy.getPartnerId());
					AccountCache.accounts.merge(partnerAQuoteCurrency, sellAmount, BigDecimal::add);
					partnerAbuyAmount = AccountCache.accounts.get(partnerAQuoteCurrency);
					
					currencyRepository.save(new Currency(partnerAQuoteCurrency, quoteCurrency, partnerAbuyAmount.toString(), buyAccount));
					
					AccountCache.accounts.merge(partnerABaseCurrency, buyAmount, BigDecimal::subtract);
					partnerASellAmount = AccountCache.accounts.get(partnerABaseCurrency);
					currencyRepository.save(new Currency(partnerABaseCurrency, baseCurrency, partnerASellAmount.toString(), buyAccount));
					
					
					// Partner B Sell QuoteCurrency for BaseCurrency
					Account sellAccount = new Account(sell.getPartnerId(), sell.getPartnerId());
					AccountCache.accounts.merge(partnerBBaseCurrency, buyAmount, BigDecimal::add);
					partnerBbuyAmount = AccountCache.accounts.get(partnerBBaseCurrency);
					currencyRepository.save(new Currency(partnerBBaseCurrency, baseCurrency, partnerBbuyAmount.toString(), sellAccount));
					
					
					AccountCache.accounts.merge(partnerBQuoteCurrency, sellAmount, BigDecimal::subtract);
					partnerBSellAmount = AccountCache.accounts.get(partnerBQuoteCurrency);
					currencyRepository.save(new Currency(partnerBQuoteCurrency, quoteCurrency, partnerBSellAmount.toString(), sellAccount));
					
					} catch(Exception e) {
						e.printStackTrace();
					} finally {
						log.info("Partner A Buy "+partnerAQuoteCurrency+" "+partnerAbuyAmount);
						log.info("Partner A Sell "+partnerABaseCurrency+" "+partnerASellAmount);
						log.info("Partner B Buy "+partnerBQuoteCurrency+" "+partnerBbuyAmount);
						log.info("Partner B Sell "+partnerBBaseCurrency+" "+partnerBSellAmount);
					}
					

					buy.setStatus(Status.REMOVE);
					sell.setStatus(Status.REMOVE);
					return true;
						
				} 
				buy.setStatus(Status.UNMATCHED);
				sell.setStatus(Status.UNMATCHED);
		}
		return false;
	}
	
	
	
	protected void writeToFile(String path, List <CurrencyOrder> orders) {
		FileWriter myWriter = null;
		 try {
			myWriter = new FileWriter(path.toString());
			for(CurrencyOrder order : orders){
				myWriter.write(order.toString());
				myWriter.write("\r");
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if(myWriter != null){
				try {
					myWriter.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}
}
