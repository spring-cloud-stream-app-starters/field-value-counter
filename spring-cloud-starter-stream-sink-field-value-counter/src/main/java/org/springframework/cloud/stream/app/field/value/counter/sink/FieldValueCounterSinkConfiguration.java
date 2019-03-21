/*
 * Copyright 2015-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.app.field.value.counter.sink;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.analytics.metrics.FieldValueCounterWriter;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.messaging.Sink;
import org.springframework.context.annotation.Import;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.transformer.MessageTransformationException;
import org.springframework.integration.tuple.JsonToTupleTransformer;
import org.springframework.messaging.Message;
import org.springframework.tuple.Tuple;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * @author Mark Pollack
 * @author David Turanski
 * @author Mark Fisher
 * @author Ilayaperumal Gopinathan
 * @author Gary Russell
 * @author Artem Bilan
 * @author Glenn Renfro
 */
@EnableBinding(Sink.class)
@Import(FieldValueCounterSinkStoreConfiguration.class)
public class FieldValueCounterSinkConfiguration {

	private static final Log log = LogFactory.getLog(FieldValueCounterSinkConfiguration.class);

	@Autowired
	private FieldValueCounterSinkProperties fvcSinkProperties;

	@Autowired
	private FieldValueCounterWriter fieldValueCounterWriter;

	private final JsonToTupleTransformer jsonToTupleTransformer = new JsonToTupleTransformer();

	@ServiceActivator(inputChannel = Sink.INPUT)
	public void process(Message<?> message) {
		Object payload = message.getPayload();
		if (payload instanceof byte[]) {
			try {
				String stringPayload = new String((byte[])payload, "UTF-8");
				payload = jsonToTupleTransformer.transformPayload(stringPayload);
			}
			catch (Exception e) {
				throw new MessageTransformationException(message, e.getMessage(), e);
			}
		}
		if (payload instanceof Tuple) {
			processTuple(computeMetricName(message), (Tuple) payload);
		}
		else {
			processPojo(computeMetricName(message), payload);
		}
	}

	private void processPojo(String counterName, Object payload) {
		String fieldName = this.fvcSinkProperties.getFieldName();

		Object value = null;
		if (payload instanceof Map) {
			Map map = (Map) payload;
			if (map.containsKey(fieldName)) {
				value = map.get(fieldName);
			}
			else {
				log.error("The property '" + fieldName + "' is not available in the payload: " + payload);
			}
		}
		else {
			BeanWrapper beanWrapper = new BeanWrapperImpl(payload);

			if (beanWrapper.isReadableProperty(fieldName)) {
				value = beanWrapper.getPropertyValue(fieldName);
			}
			else {
				log.error("The property '" + fieldName + "' is not available in the payload: " + payload);
			}
		}

		if (value != null) {
			processValue(counterName, value);
		}
		else {
			log.info("The value for the property '" + fieldName + "' in the payload '" + payload + "' is null. Ignored");
		}
	}

	private void processTuple(String counterName, Tuple tuple) {
		String[] path = StringUtils.tokenizeToStringArray(fvcSinkProperties.getFieldName(), ".");
		processValueForCounter(counterName, tuple, path);
	}

	private void processValueForCounter(String counterName, Object value, String[] path) {
		String key = path[0];
		Object result = null;
		if (value instanceof List) {
			for (Object item : (List<?>) value) {
				processValueForCounter(counterName, item, path);
			}
		}
		else if (value instanceof Tuple) {
			Tuple t = (Tuple) value;
			if (t.hasFieldName(key)) {
				result = t.getValue(key);
			}
		}
		else if (value instanceof Map) {
			result = ((Map<?, ?>) value).get(key);
		}
		if (result != null) {
			if (path.length == 1) {
				processValue(counterName, result);
			}
			else {
				path = Arrays.copyOfRange(path, 1, path.length);
				processValueForCounter(counterName, result, path);
			}
		}
		else {
			log.info("The value for the property '" + Arrays.toString(path) + "' in the payload '" +
					value + "' is null. Ignored");
		}
	}

	protected void processValue(String counterName, Object value) {
		if ((value instanceof Collection) || ObjectUtils.isArray(value)) {
			Collection<?> c = (value instanceof Collection) ? (Collection<?>) value
					: Arrays.asList(ObjectUtils.toObjectArray(value));
			for (Object val : c) {
				fieldValueCounterWriter.increment(counterName, val.toString(), 1.0);
			}
		}
		else {
			fieldValueCounterWriter.increment(counterName, value.toString(), 1.0);
		}
	}

	protected String computeMetricName(Message<?> message) {
		return fvcSinkProperties.getComputedNameExpression().getValue(message, CharSequence.class).toString();
	}


}
