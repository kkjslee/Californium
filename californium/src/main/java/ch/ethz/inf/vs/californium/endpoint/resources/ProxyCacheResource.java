/*******************************************************************************
 * Copyright (c) 2012, Institute for Pervasive Computing, ETH Zurich.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * 
 * This file is part of the Californium (Cf) CoAP framework.
 ******************************************************************************/

package ch.ethz.inf.vs.californium.endpoint.resources;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ch.ethz.inf.vs.californium.coap.DELETERequest;
import ch.ethz.inf.vs.californium.coap.GETRequest;
import ch.ethz.inf.vs.californium.coap.Option;
import ch.ethz.inf.vs.californium.coap.POSTRequest;
import ch.ethz.inf.vs.californium.coap.Request;
import ch.ethz.inf.vs.californium.coap.Response;
import ch.ethz.inf.vs.californium.coap.registries.CodeRegistry;
import ch.ethz.inf.vs.californium.coap.registries.MediaTypeRegistry;
import ch.ethz.inf.vs.californium.coap.registries.OptionNumberRegistry;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheStats;
import com.google.common.cache.LoadingCache;
import com.google.common.primitives.Ints;

/**
 * Resource to handle the caching in the proxy.
 * 
 * @author Francesco Corazza
 * 
 */
public class ProxyCacheResource extends LocalResource implements CacheResource {

	/**
	 * Time after an entry is removed. Since it is not possible to set higher
	 * values for the single instances, this constant is the upper bound for the
	 * expiration of the responses. The lifetime lower values will be handled
	 * with the max-age option.
	 */
	private static final int CACHE_RESPONSE_MAX_AGE = 60 * 60 * 24;

	/**
	 * Max size for the cache.
	 */
	private static final long CACHE_SIZE = 10000;

	/**
	 * The cache. http://code.google.com/p/guava-libraries/wiki/CachesExplained
	 */
	private final LoadingCache<CacheKey, Response> responseCache;

	private boolean enabled = true;

	/**
	 * Instantiates a new proxy cache resource.
	 */
	public ProxyCacheResource() {
		super("debug/cache");

		// builds a new cache that:
		// - has a limited size
		// - removes entries after a DEFAULT_AGE seconds after a write
		// - record statistics
		responseCache = CacheBuilder.newBuilder().maximumSize(CACHE_SIZE).recordStats().expireAfterWrite(CACHE_RESPONSE_MAX_AGE, TimeUnit.SECONDS).build(new CacheLoader<CacheKey, Response>() {
			@Override
			public Response load(CacheKey request) throws NullPointerException {
				Response cachedResponse = request.getResponse();

				if (cachedResponse == null) {
					throw new NullPointerException();
				}

				return cachedResponse;
			}
		});
	}

	/*
	 * (non-Javadoc)
	 * @see
	 * ch.ethz.inf.vs.californium.endpoint.resources.CacheResource#cacheResponse
	 * (ch.ethz.inf.vs.californium.coap.Response)
	 */
	@Override
	public void cacheResponse(Response response) {
		// enable or disable the caching (debug purposes)
		if (!enabled) {
			return;
		}

		// only the response with success codes should be cached
		int code = response.getCode();
		if (CodeRegistry.isSuccess(code)) {
			// get the request
			Request request = response.getRequest();
			CacheKey cacheKey = null;
			try {
				cacheKey = CacheKey.fromContentTypeOption(request);
			} catch (URISyntaxException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}

			if (code == CodeRegistry.RESP_CREATED || code == CodeRegistry.RESP_DELETED || code == CodeRegistry.RESP_CHANGED) {
				// the stored response should be invalidated if the response has
				// codes: 2.01, 2.02, 2.04.
				invalidateRequest(cacheKey);
			} else if (code == CodeRegistry.RESP_VALID) {
				// increase the max-age value according to the new response
				Option maxAgeOption = response.getFirstOption(OptionNumberRegistry.MAX_AGE);
				if (maxAgeOption != null) {
					// get the cached response
					Response cachedResponse = responseCache.getUnchecked(cacheKey);

					// calculate the new parameters
					long newCurrentTime = response.getTimestamp();
					int newMaxAge = maxAgeOption.getIntValue();

					// set the new parameters
					cachedResponse.getFirstOption(OptionNumberRegistry.MAX_AGE).setIntValue(newMaxAge);
					cachedResponse.setTimestamp(newCurrentTime);

					LOG.finer("Updated cached response");
				} else {
					LOG.warning("No max-age option set in response: " + response);
				}
			} else if (code == CodeRegistry.RESP_CONTENT) {
				// set max-age if not set
				Option maxAgeOption = response.getFirstOption(OptionNumberRegistry.MAX_AGE);
				if (maxAgeOption == null) {
					response.setMaxAge(Option.DEFAULT_MAX_AGE);
				}

				if (response.getMaxAge() > 0) {
					// cache the request
					try {
						// Caches loaded by a CacheLoader will call
						// CacheLoader.load(K) to load new values into the cache
						// when used the get method.
						Response responseInserted = responseCache.get(cacheKey);
						if (responseInserted != null) {
							LOG.finer("Cached response");
						} else {
							LOG.warning("Failed to insert the response in the cache");
						}
					} catch (Exception e) {
						// swallow
						LOG.warning("Exception while inserting the response in the cache: " + e.getMessage());
					}
				} else {
					// if the max-age option is set to 0, then the response
					// should be invalidated
					invalidateRequest(request);
				}
			} else {
				// this code should not be reached
				LOG.severe("Code not recognized: " + code);
			}
		}
	}

	@Override
	public CacheStats getCacheStats() {
		return responseCache.stats();
	}

	/*
	 * (non-Javadoc)
	 * @see
	 * ch.ethz.inf.vs.californium.endpoint.resources.CacheResource#getResponse
	 * (ch.ethz.inf.vs.californium.coap.Request)
	 */
	@Override
	public Response getResponse(Request request) {
		if (!enabled) {
			return null;
		}

		// search the desired representation
		Response response = null;
		CacheKey cacheKey = null;
		try {
			for (CacheKey acceptKey : CacheKey.fromAcceptOptions(request)) {
				response = responseCache.getIfPresent(acceptKey);
				cacheKey = acceptKey;

				if (response != null) {
					break;
				}
			}
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// if the response is not null, manage the cached response
		if (response != null) {
			LOG.finer("Cache hit");

			// check if the response is expired
			long currentTime = System.nanoTime();
			int nanosLeft = getRemainingLifetime(response, currentTime);
			if (nanosLeft > 0) {
				// if the response can be used, then update its max-age to
				// consider the aging of the response while in the cache
				response.setMaxAge(nanosLeft);
				// set the current time as the response timestamp
				response.setTimestamp(currentTime);
			} else {
				LOG.finer("Expired response");

				// try to validate the response
				response = validate(cacheKey);
				if (response != null) {
					LOG.finer("Validation successful");
				} else {
					invalidateRequest(cacheKey);
				}
			}
		}

		return response;
	}

	/*
	 * (non-Javadoc)
	 * @see ch.ethz.inf.vs.californium.endpoint.resources.CacheResource#
	 * invalidateResponse(ch.ethz.inf.vs.californium.coap.Response)
	 */
	@Override
	public void invalidateRequest(Request request) {
		try {
			invalidateRequest(CacheKey.fromAcceptOptions(request));
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		LOG.finer("Invalidated request");
	}

	@Override
	public void performDELETE(DELETERequest request) {
		responseCache.invalidateAll();
		request.respond(CodeRegistry.RESP_DELETED);
	}

	@Override
	public void performGET(GETRequest request) {
		StringBuilder builder = new StringBuilder();
		builder.append("Available commands:\n - GET: show cached values\n - DELETE: empty the cache\n - POST: enable/disable caching\n");

		// get cache values
		builder.append("\nCached values:\n");
		for (CacheKey cachedRequest : responseCache.asMap().keySet()) {
			Response response = responseCache.asMap().get(cachedRequest);

			builder.append(cachedRequest.getProxyUri().toString() + " (" + MediaTypeRegistry.toString(cachedRequest.getMediaType()) + ") > " + getRemainingLifetime(response) + " seconds | (" + cachedRequest.getMediaType() + ")\n");
		}

		request.respond(CodeRegistry.RESP_CONTENT, builder.toString());
	}

	@Override
	public void performPOST(POSTRequest request) {
		enabled = !enabled;
		String content = enabled ? "Enabled" : "Disabled";
		request.respond(CodeRegistry.RESP_CHANGED, content);
	}

	private int getRemainingLifetime(Response response) {
		return getRemainingLifetime(response, System.nanoTime());
	}

	/**
	 * Method that checks if the lifetime allowed for the response if expired.
	 * The result is calculated with the initial timestamp (when the response
	 * has been received) and the max-age option compared against the current
	 * timestamp. If the max-age option is not specified, it will be assumed the
	 * default (60 seconds).
	 * 
	 * @param response
	 *            the response
	 * @param currentTime
	 * @return true, if is expired
	 */
	private int getRemainingLifetime(Response response, long currentTime) {
		// get the timestamp
		long arriveTime = response.getTimestamp();

		Option maxAgeOption = response.getFirstOption(OptionNumberRegistry.MAX_AGE);
		int oldMaxAge = Option.DEFAULT_MAX_AGE;
		if (maxAgeOption != null) {
			oldMaxAge = maxAgeOption.getIntValue();
		}

		// calculate the time that the response has spent in the cache
		double secondsInCache = TimeUnit.NANOSECONDS.toSeconds(currentTime - arriveTime);
		int cacheTime = Ints.checkedCast(Math.round(secondsInCache));
		return oldMaxAge - cacheTime;
	}

	private void invalidateRequest(CacheKey cacheKey) {
		responseCache.invalidate(cacheKey);
	}

	private void invalidateRequest(List<CacheKey> cacheKeys) {
		responseCache.invalidateAll(cacheKeys);
	}

	private Response validate(CacheKey cachedRequest) {
		// TODO
		return null;
	}

	private final class AdditionalCacheStats {
		// TODO ?
		private int cacheSize;
		private int invalidateCount;
	}

	/**
	 * Nested class to store a request as a key in the cache. It is needed to
	 * normalize the variable fields of the normal requests.
	 * 
	 * @author Francesco Corazza
	 */
	private static final class CacheKey {
		private final String proxyUri;
		private final int mediaType;
		private Response response;
		private final byte[] payload;

		/**
		 * From request.
		 * 
		 * @param request
		 *            the request
		 * @return the cached request
		 * @throws URISyntaxException
		 */
		private static List<CacheKey> fromAcceptOptions(Request request) throws URISyntaxException {
			if (request == null) {
				throw new IllegalArgumentException("request == null");
			}

			List<CacheKey> cacheKeys = new LinkedList<ProxyCacheResource.CacheKey>();
			String proxyUri = request.getProxyUri().toString();
			byte[] payload = request.getPayload();
			List<Option> acceptOptions = request.getOptions(OptionNumberRegistry.ACCEPT);
			if (acceptOptions != null) {
				for (Option acceptOption : acceptOptions) {
					int mediaType = acceptOption.getIntValue();
					CacheKey cacheKey = new CacheKey(proxyUri, mediaType, payload);
					cacheKeys.add(cacheKey);
				}
			} else {
				// if the accept options are not set, simply set all media types
				// FIXME not efficient
				for (Integer acceptType : MediaTypeRegistry.getAllMediaTypes()) {
					CacheKey cacheKey = new CacheKey(proxyUri, acceptType, payload);
					cacheKeys.add(cacheKey);
				}
			}

			return cacheKeys;
		}

		private static CacheKey fromContentTypeOption(Request request) throws URISyntaxException {
			if (request == null) {
				throw new IllegalArgumentException("request == null");
			}

			Response response = request.getResponse();
			if (response == null) {
				return fromAcceptOptions(request).get(0);
			}

			String proxyUri = request.getProxyUri().toString();
			int mediaType = response.getContentType();
			byte[] payload = request.getPayload();

			// create the new cacheKey
			CacheKey cacheKey = new CacheKey(proxyUri, mediaType, payload);
			cacheKey.setResponse(response);

			return cacheKey;
		}

		public CacheKey(String proxyUri, int mediaType, byte[] payload) {
			this.proxyUri = proxyUri;
			this.mediaType = mediaType;
			this.payload = payload;
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			CacheKey other = (CacheKey) obj;
			if (mediaType != other.mediaType) {
				return false;
			}
			if (!Arrays.equals(payload, other.payload)) {
				return false;
			}
			if (proxyUri == null) {
				if (other.proxyUri != null) {
					return false;
				}
			} else if (!proxyUri.equals(other.proxyUri)) {
				return false;
			}
			return true;
		}

		/**
		 * @return the mediaType
		 */
		public int getMediaType() {
			return mediaType;
		}

		/**
		 * @return the proxyUri
		 */
		public String getProxyUri() {
			return proxyUri;
		}

		/**
		 * @return the response
		 */
		public Response getResponse() {
			return response;
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#hashCode()
		 */
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + mediaType;
			result = prime * result + Arrays.hashCode(payload);
			result = prime * result + (proxyUri == null ? 0 : proxyUri.hashCode());
			return result;
		}

		private void setResponse(Response response) {
			this.response = response;

		}
	}
}