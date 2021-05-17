package com.cav.currencyexchange.service.matching;


import com.cav.currencyexchange.repository.CurrencyRepository;

public interface MatchingService {

	
	public void matchingServiceMultiProcessing(String currencyKeyPair, CurrencyRepository currencyRepository);
}
