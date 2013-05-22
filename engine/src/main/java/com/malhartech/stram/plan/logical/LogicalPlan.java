/**
 * Copyright (c) 2012-2012 Malhar, Inc.
 * All rights reserved.
 */
package com.malhartech.stram.plan.logical;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Externalizable;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.malhartech.annotation.InputPortFieldAnnotation;
import com.malhartech.annotation.OutputPortFieldAnnotation;
import com.malhartech.api.AttributeMap;
import com.malhartech.api.AttributeMap.DefaultAttributeMap;
import com.malhartech.api.BaseOperator;
import com.malhartech.api.Context.OperatorContext;
import com.malhartech.api.Context.PortContext;
import com.malhartech.api.DAG;
import com.malhartech.api.DAGContext;
import com.malhartech.api.DefaultOperatorSerDe;
import com.malhartech.api.Operator;
import com.malhartech.api.Operator.InputPort;
import com.malhartech.api.Operator.OutputPort;
import com.malhartech.api.Operators;
import com.malhartech.api.StreamCodec;

/**
 * DAG contains the logical declarations of operators and streams.
 * <p>
 * Operators have ports that are connected through streams. Ports can be
 * mandatory or optional with respect to their need to connect a stream to it.
 * Each port can be connected to a single stream only. A stream has to be
 * connected to one output port and can go to multiple input ports.
 * <p>
 * The DAG will be serialized and deployed to the cluster, where it is translated
 * into the physical plan.
 */
public class LogicalPlan implements Serializable, DAG
{
  private static final long serialVersionUID = -2099729915606048704L;
  private static final Logger LOG = LoggerFactory.getLogger(LogicalPlan.class);
  private final Map<String, StreamMeta> streams = new HashMap<String, StreamMeta>();
  private final Map<String, OperatorMeta> operators = new HashMap<String, OperatorMeta>();
  private final List<OperatorMeta> rootOperators = new ArrayList<OperatorMeta>();
  private final AttributeMap<DAGContext> attributes = new DefaultAttributeMap<DAGContext>();
  private transient int nodeIndex = 0; // used for cycle validation
  private transient Stack<OperatorMeta> stack = new Stack<OperatorMeta>(); // used for cycle validation

  public static class ExternalizableModule implements Externalizable
  {
    private Operator module;

    private void set(Operator module)
    {
      this.module = module;
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException
    {
      int len = in.readInt();
      byte[] bytes = new byte[len];
      in.read(bytes);
      ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
      set((Operator)new DefaultOperatorSerDe().read(bis));
      bis.close();
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException
    {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      new DefaultOperatorSerDe().write(module, bos);
      bos.close();
      byte[] bytes = bos.toByteArray();
      out.writeInt(bytes.length);
      out.write(bytes);
    }
  }

  public LogicalPlan()
  {
  }

  @SuppressWarnings("unchecked")
  public LogicalPlan(Configuration conf)
  {
    for (@SuppressWarnings("rawtypes") DAGContext.AttributeKey key : DAGContext.ATTRIBUTE_KEYS) {
      String stringValue = conf.get(key.name());
      if (stringValue != null) {
        if (key.attributeType == Integer.class) {
          this.attributes.attr((DAGContext.AttributeKey<Integer>)key).set(conf.getInt(key.name(), 0));
        } else if (key.attributeType == Long.class) {
          this.attributes.attr((DAGContext.AttributeKey<Long>)key).set(conf.getLong(key.name(), 0));
        } else if (key.attributeType == String.class) {
          this.attributes.attr((DAGContext.AttributeKey<String>)key).set(stringValue);
        } else if (key.attributeType == Boolean.class) {
          this.attributes.attr((DAGContext.AttributeKey<Boolean>)key).set(conf.getBoolean(key.name(), false));
        } else {
          String msg = String.format("Unsupported attribute type: %s (%s)", key.attributeType, key.name());
          throw new UnsupportedOperationException(msg);
        }
      }
    }
  }

  public final class InputPortMeta implements DAG.InputPortMeta, Serializable
  {
    private static final long serialVersionUID = 1L;
    private OperatorMeta operatorWrapper;
    private String fieldName;
    private InputPortFieldAnnotation portAnnotation;
    private final AttributeMap<PortContext> attributes = new DefaultAttributeMap<PortContext>();

    public OperatorMeta getOperatorWrapper()
    {
      return operatorWrapper;
    }

    public String getPortName()
    {
      return portAnnotation == null || portAnnotation.name() == null ? fieldName : portAnnotation.name();
    }

    public InputPort<?> getPortObject() {
      for (Entry<InputPort<?>, InputPortMeta> e : operatorWrapper.getPortMapping().inPortMap.entrySet()) {
        if (e.getValue() == this) {
          return e.getKey();
        }
      }
      throw new AssertionError("Cannot find the port object for " + this);
    }

    @Override
    public AttributeMap<PortContext> getAttributes() {
      return attributes;
    }

    @Override
    public String toString()
    {
      return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).
              append("operator", this.operatorWrapper).
              append("portAnnotation", this.portAnnotation).
              append("field", this.fieldName).
              toString();
    }
  }

  public final class OutputPortMeta implements DAG.OutputPortMeta, Serializable
  {
    private static final long serialVersionUID = 1L;
    private OperatorMeta operatorWrapper;
    private String fieldName;
    private OutputPortFieldAnnotation portAnnotation;
    private final DefaultAttributeMap<PortContext> attributes = new DefaultAttributeMap<PortContext>();

    public OperatorMeta getOperatorWrapper()
    {
      return operatorWrapper;
    }

    public String getPortName()
    {
      return portAnnotation == null || portAnnotation.name() == null ? fieldName : portAnnotation.name();
    }

    public Operator.Unifier<?> getUnifier() {
      for (Entry<OutputPort<?>, OutputPortMeta> e : operatorWrapper.getPortMapping().outPortMap.entrySet()) {
        if (e.getValue() == this) {
          return e.getKey().getUnifier();
        }
      }
      return null;
    }

    @Override
    public AttributeMap<PortContext> getAttributes() {
      return this.attributes;
    }

    @Override
    public String toString()
    {
      return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).
              append("operator", this.operatorWrapper).
              append("portAnnotation", this.portAnnotation).
              append("field", this.fieldName).
              toString();
    }
  }

  /**
   * Representation of streams in the logical layer. Instances are created through {@link LogicalPlan.addStream}.
   */
  public final class StreamMeta implements DAG.StreamMeta, Serializable
  {
    private static final long serialVersionUID = 1L;
    private boolean inline;
    private boolean nodeLocal;
    private final List<InputPortMeta> sinks = new ArrayList<InputPortMeta>();
    private OutputPortMeta source;
    private Class<? extends StreamCodec<?>> serDeClass;
    private final String id;

    private StreamMeta(String id)
    {
      this.id = id;
    }

    @Override
    public String getId()
    {
      return id;
    }

    /**
     * Hint to manager that adjacent operators should be deployed in same container.
     *
     * @return boolean
     */
    @Override
    public boolean isInline()
    {
      return inline;
    }

    @Override
    public StreamMeta setInline(boolean inline)
    {
      this.inline = inline;
      return this;
    }

    @Override
    public boolean isNodeLocal()
    {
      return this.nodeLocal;
    }

    @Override
    public StreamMeta setNodeLocal(boolean local)
    {
      this.nodeLocal = local;
      return this;
    }

    public Class<? extends StreamCodec<?>> getCodecClass()
    {
      return serDeClass;
    }

    public OutputPortMeta getSource()
    {
      return source;
    }

    @Override
    public StreamMeta setSource(Operator.OutputPort<?> port)
    {
      OperatorMeta op = getMeta(port.getOperator());
      OutputPortMeta portMeta = op.getMeta(port);
      if (portMeta == null) {
        throw new IllegalArgumentException("Invalid port reference " + port);
      }
      this.source = portMeta;
      if (op.outputStreams.containsKey(portMeta)) {
        String msg = String.format("Operator %s already connected to %s", op.id, op.outputStreams.get(portMeta).id);
        throw new IllegalArgumentException(msg);
      }
      op.outputStreams.put(portMeta, this);
      return this;
    }

    public List<InputPortMeta> getSinks()
    {
      return sinks;
    }

    @Override
    public StreamMeta addSink(Operator.InputPort<?> port)
    {
      OperatorMeta op = getMeta(port.getOperator());
      InputPortMeta portMeta = op.getMeta(port);
      if (portMeta == null) {
        throw new IllegalArgumentException("Invalid port reference " + port);
      }
      String portName = portMeta.getPortName();
      if (op.inputStreams.containsKey(portMeta)) {
        throw new IllegalArgumentException(String.format("Port %s already connected to stream %s", portName, op.inputStreams.get(portMeta)));
      }

      // determine codec for the stream based on what was set on the ports
      Class<? extends StreamCodec<?>> codecClass = port.getStreamCodec();
      if (codecClass != null) {
        if (this.serDeClass != null && !this.serDeClass.equals(codecClass)) {
          String msg = String.format("Conflicting codec classes set on input port %s (%s) when %s was specified earlier.", codecClass, portMeta, this.serDeClass);
          throw new IllegalArgumentException(msg);
        }
        this.serDeClass = codecClass;
      }

      sinks.add(portMeta);
      op.inputStreams.put(portMeta, this);
      rootOperators.remove(portMeta.operatorWrapper);

      return this;
    }

    @Override
    public String toString()
    {
      return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).
              append("id", this.id).
              toString();
    }

  }

  /**
   * Operator meta object. Intended for internal use.
   */
  public final class OperatorMeta implements DAG.OperatorMeta, Serializable
  {
    private static final long serialVersionUID = 1L;
    private final LinkedHashMap<InputPortMeta, StreamMeta> inputStreams = new LinkedHashMap<InputPortMeta, StreamMeta>();
    private final LinkedHashMap<OutputPortMeta, StreamMeta> outputStreams = new LinkedHashMap<OutputPortMeta, StreamMeta>();
    private final AttributeMap<OperatorContext> attributes = new DefaultAttributeMap<OperatorContext>();
    private final ExternalizableModule moduleHolder;
    private final String id;
    private transient Integer nindex; // for cycle detection
    private transient Integer lowlink; // for cycle detection

    private OperatorMeta(String id, Operator module)
    {
      this.moduleHolder = new ExternalizableModule();
      this.moduleHolder.set(module);
      this.id = id;
    }

    public String getId()
    {
      return id;
    }

    private class PortMapping implements Operators.OperatorDescriptor
    {
      private final Map<Operator.InputPort<?>, InputPortMeta> inPortMap = new HashMap<Operator.InputPort<?>, InputPortMeta>();
      private final Map<Operator.OutputPort<?>, OutputPortMeta> outPortMap = new HashMap<Operator.OutputPort<?>, OutputPortMeta>();
      private final Map<String, Object> portNameMap = new HashMap<String, Object>();

      @Override
      public void addInputPort(InputPort<?> portObject, Field field, InputPortFieldAnnotation a)
      {
        if (!OperatorMeta.this.inputStreams.isEmpty()) {
          for (Map.Entry<LogicalPlan.InputPortMeta, LogicalPlan.StreamMeta> e : OperatorMeta.this.inputStreams.entrySet()) {
            LogicalPlan.InputPortMeta pm = e.getKey();
            if (pm.operatorWrapper == OperatorMeta.this && pm.fieldName.equals(field.getName())) {
              //LOG.debug("Found existing port meta for: " + field);
              inPortMap.put(portObject, pm);
              checkDuplicateName(pm.getPortName(), pm);
              return;
            }
          }
        }
        InputPortMeta metaPort = new InputPortMeta();
        metaPort.operatorWrapper = OperatorMeta.this;
        metaPort.fieldName = field.getName();
        metaPort.portAnnotation = a;
        inPortMap.put(portObject, metaPort);
        checkDuplicateName(metaPort.getPortName(), metaPort);
      }

      @Override
      public void addOutputPort(OutputPort<?> portObject, Field field, OutputPortFieldAnnotation a)
      {
        if (!OperatorMeta.this.outputStreams.isEmpty()) {
          for (Map.Entry<LogicalPlan.OutputPortMeta, LogicalPlan.StreamMeta> e : OperatorMeta.this.outputStreams.entrySet()) {
            LogicalPlan.OutputPortMeta pm = e.getKey();
            if (pm.operatorWrapper == OperatorMeta.this && pm.fieldName.equals(field.getName())) {
              //LOG.debug("Found existing port meta for: " + field);
              outPortMap.put(portObject, pm);
              checkDuplicateName(pm.getPortName(), pm);
              return;
            }
          }
        }
        OutputPortMeta metaPort = new OutputPortMeta();
        metaPort.operatorWrapper = OperatorMeta.this;
        metaPort.fieldName = field.getName();
        metaPort.portAnnotation = a;
        outPortMap.put(portObject, metaPort);
        checkDuplicateName(metaPort.getPortName(), metaPort);
      }

      private void checkDuplicateName(String portName, Object portMeta) {
        Object existingValue = portNameMap.put(portName, portMeta);
        if (existingValue != null) {
          String msg = String.format("Port name %s of %s duplicates %s", portName, portMeta, existingValue);
          throw new IllegalArgumentException(msg);
        }
      }
    }
    /**
     * Ports objects are transient, we keep a lazy initialized mapping
     */
    private transient PortMapping portMapping = null;

    private PortMapping getPortMapping()
    {
      if (this.portMapping == null) {
        this.portMapping = new PortMapping();
        Operators.describe(this.getOperator(), portMapping);
      }
      return portMapping;
    }

    @Override
    public OutputPortMeta getMeta(Operator.OutputPort<?> port)
    {
      return getPortMapping().outPortMap.get(port);
    }

    @Override
    public InputPortMeta getMeta(Operator.InputPort<?> port)
    {
      return getPortMapping().inPortMap.get(port);
    }

    public Map<InputPortMeta, StreamMeta> getInputStreams()
    {
      return this.inputStreams;
    }

    public Map<OutputPortMeta, StreamMeta> getOutputStreams()
    {
      return this.outputStreams;
    }

    @Override
    public Operator getOperator()
    {
      return this.moduleHolder.module;
    }

    public AttributeMap<OperatorContext> getAttributes()
    {
      return this.attributes;
    }

    @Override
    public String toString()
    {
      return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).
              append("id", this.id).
              append("operator", this.getOperator().getClass().getSimpleName()).
              toString();
    }
  }

  /* (non-Javadoc)
   * @see com.malhartech.stram.plan.logical.DAGIF#addOperator(java.lang.String, java.lang.Class)
   */
  @Override
  public <T extends Operator> T addOperator(String name, Class<T> clazz)
  {
    T instance;
    try {
      instance = clazz.newInstance();
    } catch (Exception ex) {
      throw new IllegalArgumentException(ex);
    }
    addOperator(name, instance);
    return instance;
  }

  /* (non-Javadoc)
   * @see com.malhartech.stram.plan.logical.DAGIF#addOperator(java.lang.String, T)
   */
  @Override
  public <T extends Operator> T addOperator(String name, T operator)
  {
    // TODO: optional interface to provide contextual information to instance
    if (operator instanceof BaseOperator) {
      ((BaseOperator)operator).setName(name);
    }
    if (operators.containsKey(name)) {
      if (operators.get(name) == (Object)operator) {
        return operator;
      }
      throw new IllegalArgumentException("duplicate operator id: " + operators.get(name));
    }

    OperatorMeta decl = new OperatorMeta(name, operator);
    rootOperators.add(decl);
    operators.put(name, decl);
    return operator;
  }

  /* (non-Javadoc)
   * @see com.malhartech.stram.plan.logical.DAGIF#addStream(java.lang.String)
   */
  @Override
  public StreamMeta addStream(String id)
  {
    StreamMeta s = new StreamMeta(id);
    StreamMeta o = streams.put(id, s);
    if (o == null) {
      return s;
    }

    throw new IllegalArgumentException("duplicate stream id: " + o);
  }

  /* (non-Javadoc)
   * @see com.malhartech.stram.plan.logical.DAGIF#addStream(java.lang.String, com.malhartech.api.Operator.OutputPort, com.malhartech.api.Operator.InputPort)
   */
  @Override
  public <T> StreamMeta addStream(String id, Operator.OutputPort<? extends T> source, Operator.InputPort<? super T>... sinks)
  {
    StreamMeta s = addStream(id);
    s.setSource(source);
    for (Operator.InputPort<?> sink: sinks) {
      s.addSink(sink);
    }
    return s;
  }

  /* (non-Javadoc)
   * @see com.malhartech.stram.plan.logical.DAGIF#addStream(java.lang.String, com.malhartech.api.Operator.OutputPort, com.malhartech.api.Operator.InputPort)
   */
  @Override
  @SuppressWarnings("unchecked")
  public <T> StreamMeta addStream(String id, Operator.OutputPort<? extends T> source, Operator.InputPort<? super T> sink1)
  {
    return addStream(id, source, new Operator.InputPort[] {sink1});
  }

  /* (non-Javadoc)
   * @see com.malhartech.stram.plan.logical.DAGIF#addStream(java.lang.String, com.malhartech.api.Operator.OutputPort, com.malhartech.api.Operator.InputPort, com.malhartech.api.Operator.InputPort)
   */
  @Override
  @SuppressWarnings("unchecked")
  public <T> StreamMeta addStream(String id, Operator.OutputPort<? extends T> source, Operator.InputPort<? super T> sink1, Operator.InputPort<? super T> sink2)
  {
    return addStream(id, source, new Operator.InputPort[] {sink1, sink2});
  }

  public StreamMeta getStream(String id)
  {
    return this.streams.get(id);
  }

  /**
   * Set attribute for the operator. For valid attributes, see {
   *
   * @param operator
   * @return AttributeMap<OperatorContext>
   */
  public AttributeMap<OperatorContext> getContextAttributes(Operator operator)
  {
    return getMeta(operator).attributes;
  }

  /* (non-Javadoc)
   * @see com.malhartech.stram.plan.logical.DAGIF#setAttribute(com.malhartech.api.DAGContext.AttributeKey, T)
   */
  @Override
  public <T> void setAttribute(DAGContext.AttributeKey<T> key, T value)
  {
    this.getAttributes().attr(key).set(value);
  }

  /* (non-Javadoc)
   * @see com.malhartech.stram.plan.logical.DAGIF#setAttribute(com.malhartech.api.Operator, com.malhartech.api.Context.OperatorContext.AttributeKey, T)
   */
  @Override
  public <T> void setAttribute(Operator operator, OperatorContext.AttributeKey<T> key, T value)
  {
    this.getMeta(operator).attributes.attr(key).set(value);
  }

  /* (non-Javadoc)
   * @see com.malhartech.stram.plan.logical.DAGIF#setOutputPortAttribute(com.malhartech.api.Operator.OutputPort, com.malhartech.api.Context.PortContext.AttributeKey, T)
   */
  @Override
  public <T> void setOutputPortAttribute(Operator.OutputPort<?> port, PortContext.AttributeKey<T> key, T value)
  {
    getMeta(port.getOperator()).getPortMapping().outPortMap.get(port).attributes.attr(key).set(value);
  }

  /* (non-Javadoc)
   * @see com.malhartech.stram.plan.logical.DAGIF#setInputPortAttribute(com.malhartech.api.Operator.InputPort, com.malhartech.api.Context.PortContext.AttributeKey, T)
   */
  @Override
  public <T> void setInputPortAttribute(Operator.InputPort<?> port, PortContext.AttributeKey<T> key, T value)
  {
    getMeta(port.getOperator()).getPortMapping().inPortMap.get(port).attributes.attr(key).set(value);
  }

  public List<OperatorMeta> getRootOperators()
  {
    return Collections.unmodifiableList(this.rootOperators);
  }

  public Collection<OperatorMeta> getAllOperators()
  {
    return Collections.unmodifiableCollection(this.operators.values());
  }

  public Collection<StreamMeta> getAllStreams()
  {
    return Collections.unmodifiableCollection(this.streams.values());
  }

  @Override
  public OperatorMeta getOperatorMeta(String operatorId)
  {
    return this.operators.get(operatorId);
  }

  @Override
  public OperatorMeta getMeta(Operator operator)
  {
    // TODO: cache mapping
    for (OperatorMeta o: getAllOperators()) {
      if (o.moduleHolder.module == operator) {
        return o;
      }
    }
    throw new IllegalArgumentException("Operator not associated with the DAG: " + operator);
  }

  @Override
  public AttributeMap<DAGContext> getAttributes() {
    return this.attributes;
  }

  public int getMaxContainerCount()
  {
    return this.attributes.attrValue(STRAM_MAX_CONTAINERS, 3);
  }

  public boolean isDebug()
  {
    return this.attributes.attrValue(STRAM_DEBUG, false);
  }

  public int getContainerMemoryMB()
  {
    return this.attributes.attrValue(STRAM_CONTAINER_MEMORY_MB, 1024);
  }

  public int getMasterMemoryMB()
  {
    return this.attributes.attrValue(STRAM_MASTER_MEMORY_MB, 1024);
  }

  /**
   * Class dependencies for the topology. Used to determine jar file dependencies.
   *
   * @return Set<String>
   */
  public Set<String> getClassNames()
  {
    Set<String> classNames = new HashSet<String>();
    for (OperatorMeta n: this.operators.values()) {
      String className = n.getOperator().getClass().getName();
      if (className != null) {
        classNames.add(className);
      }
    }
    for (StreamMeta n: this.streams.values()) {
      if (n.serDeClass != null) {
        classNames.add(n.serDeClass.getName());
      }
    }
    return classNames;
  }

  /**
   * Validate the topology. Includes checks that required ports are connected,
   * required configuration parameters specified, graph free of cycles etc.
   */
  public void validate() throws ConstraintViolationException
  {
    ValidatorFactory factory =
            Validation.buildDefaultValidatorFactory();
    Validator validator = factory.getValidator();

    // clear visited on all operators
    for (OperatorMeta n: operators.values()) {
      n.nindex = null;
      n.lowlink = null;

      // validate configuration
      Set<ConstraintViolation<Operator>> constraintViolations = validator.validate(n.getOperator());
      if (!constraintViolations.isEmpty()) {
        Set<ConstraintViolation<?>> copySet = new HashSet<ConstraintViolation<?>>(constraintViolations.size());
        // workaround bug in ConstraintViolationException constructor
        // (should be public <T> ConstraintViolationException(String message, Set<ConstraintViolation<T>> constraintViolations) { ... })
        for (ConstraintViolation<Operator> cv: constraintViolations) {
          copySet.add(cv);
        }
        throw new ConstraintViolationException("Operator " + n.getId() + " violates constraints", copySet);
      }

      // check that non-optional ports are connected
      OperatorMeta.PortMapping portMapping = n.getPortMapping();
      for (InputPortMeta pm: portMapping.inPortMap.values()) {
        if (!n.inputStreams.containsKey(pm)) {
          if (pm.portAnnotation != null && !pm.portAnnotation.optional()) {
            throw new IllegalArgumentException("Input port connection required: " + n.id + "." + pm.getPortName());
          }
        }
      }

      boolean allPortsOptional = true;
      for (OutputPortMeta pm: portMapping.outPortMap.values()) {
        if (!n.outputStreams.containsKey(pm)) {
          if (pm.portAnnotation != null && !pm.portAnnotation.optional()) {
            throw new IllegalArgumentException("Output port connection required: " + n.id + "." + pm.getPortName());
          }
        }
        allPortsOptional &= (pm.portAnnotation != null && pm.portAnnotation.optional());
      }
      if (!allPortsOptional && n.outputStreams.isEmpty()) {
        throw new IllegalArgumentException("At least one output port must be connected: " + n.id);
      }
    }
    stack = new Stack<OperatorMeta>();

    List<List<String>> cycles = new ArrayList<List<String>>();
    for (OperatorMeta n: operators.values()) {
      if (n.nindex == null) {
        findStronglyConnected(n, cycles);
      }
    }
    if (!cycles.isEmpty()) {
      throw new IllegalStateException("Loops detected in the graph: " + cycles);
    }

    for (StreamMeta s: streams.values()) {
      if (s.source == null && (s.sinks.isEmpty())) {
        throw new IllegalStateException(String.format("stream needs to be connected to at least on node %s", s.getId()));
      }
    }
  }

  /**
   * Check for cycles in the graph reachable from start node n. This is done by
   * attempting to find a strongly connected components.
   *
   * @see http://en.wikipedia.org/wiki/Tarjan%E2%80%99s_strongly_connected_components_algorithm
   *
   * @param n
   * @param cycles
   */
  public void findStronglyConnected(OperatorMeta n, List<List<String>> cycles)
  {
    n.nindex = nodeIndex;
    n.lowlink = nodeIndex;
    nodeIndex++;
    stack.push(n);

    // depth first successors traversal
    for (StreamMeta downStream: n.outputStreams.values()) {
      for (InputPortMeta sink: downStream.sinks) {
        OperatorMeta successor = sink.getOperatorWrapper();
        if (successor == null) {
          continue;
        }
        // check for self referencing node
        if (n == successor) {
          cycles.add(Collections.singletonList(n.id));
        }
        if (successor.nindex == null) {
          // not visited yet
          findStronglyConnected(successor, cycles);
          n.lowlink = Math.min(n.lowlink, successor.lowlink);
        }
        else if (stack.contains(successor)) {
          n.lowlink = Math.min(n.lowlink, successor.nindex);
        }
      }
    }

    // pop stack for all root operators
    if (n.lowlink.equals(n.nindex)) {
      List<String> connectedIds = new ArrayList<String>();
      while (!stack.isEmpty()) {
        OperatorMeta n2 = stack.pop();
        connectedIds.add(n2.id);
        if (n2 == n) {
          break; // collected all connected operators
        }
      }
      // strongly connected (cycle) if more than one node in stack
      if (connectedIds.size() > 1) {
        LOG.debug("detected cycle from node {}: {}", n.id, connectedIds);
        cycles.add(connectedIds);
      }
    }
  }

  public static void write(DAG dag, OutputStream os) throws IOException
  {
    ObjectOutputStream oos = new ObjectOutputStream(os);
    oos.writeObject(dag);
  }

  public static LogicalPlan read(InputStream is) throws IOException, ClassNotFoundException
  {
    return (LogicalPlan)new ObjectInputStream(is).readObject();
  }

  @Override
  public String toString()
  {
    return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).
            append("operators", this.operators).
            append("streams", this.streams).
            append("properties", this.attributes).
            toString();
  }
}
