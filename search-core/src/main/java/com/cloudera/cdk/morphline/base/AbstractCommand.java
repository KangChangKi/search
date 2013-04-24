/**
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.cdk.morphline.base;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.cdk.morphline.api.Command;
import com.cloudera.cdk.morphline.api.CommandBuilder;
import com.cloudera.cdk.morphline.api.Configs;
import com.cloudera.cdk.morphline.api.MorphlineContext;
import com.cloudera.cdk.morphline.api.MorphlineParsingException;
import com.cloudera.cdk.morphline.api.Record;
import com.google.common.base.Preconditions;
import com.typesafe.config.Config;

/**
 * Base class for convenient implementation of {@link Command} classes.
 */
public abstract class AbstractCommand implements Command {
  
  private Config config;
  private Command parent;
  private Command child;
  private MorphlineContext context;
  
  protected final Logger LOG = LoggerFactory.getLogger(getClass());
      
  public AbstractCommand(Config config, Command parent, Command child, MorphlineContext context) {
    Preconditions.checkNotNull(config);
    Preconditions.checkNotNull(parent);
    Preconditions.checkNotNull(child);
    Preconditions.checkNotNull(context);
    this.config = config;
    this.parent = parent;
    this.child = child;
    this.context = context;
  }
  
  @Override
  public Command getParent() {
    return parent;
  }
  
  protected Command getChild() {
    return child;
  }
  
  protected Config getConfig() {
    return config;
  }
  
  protected MorphlineContext getContext() {
    return context;
  }
  
  @Override
  public void startSession() {
    LOG.debug("startSession()");
    getChild().startSession();
  }

  @Override
  public boolean process(Record record) {
    LOG.debug("process()");
    return getChild().process(record);
  }
  
  /**
   * Factory method to create the chain of commands rooted at the given rootConfig. The last command
   * in the chain will feed records into finalChild.
   * 
   * @param isRule
   *          if true indicates don't forward startSession() at the end of the chain of commands of a
   *          rule.
   */
  protected List<Command> buildCommandChain(Config rootConfig, String configKey, Command finalChild, boolean isRule) {    
    List<? extends Config> commandConfigs = Configs.getConfigList(rootConfig, configKey, Collections.EMPTY_LIST);
    List<Command> commands = new ArrayList();
    Command currentParent = this;
    Connector lastConnector = null;        
    for (int i = 0; i < commandConfigs.size(); i++) {
      boolean isLast = (i == commandConfigs.size() - 1);
      Connector connector = new Connector(isRule && isLast);
      if (isLast) {
        connector.setChild(finalChild);
      }
      Config cmdConfig = commandConfigs.get(i);
      Command cmd = buildCommand(cmdConfig, currentParent, connector);
      commands.add(cmd);
      if (i > 0) {
        lastConnector.setChild(cmd);
      }
      connector.setParent(cmd);
      currentParent = connector;
      lastConnector = connector;
    }
    return commands;
  }

  /**
   * Factory method to create a command rooted at the given cmdConfig. The command will feed records
   * into finalChild. The command will have currentParent as it's parent.
   */
  protected Command buildCommand(Config cmdConfig, Command currentParent, Command finalChild) {    
    //LOG.info("cmdConfig {}", cmdConfig);
    LOG.trace("unwrapped {}", cmdConfig.root().unwrapped());    
    Set<Map.Entry<String, Object>> entries = cmdConfig.root().unwrapped().entrySet();
    if (entries.size() != 1) {
      throw new MorphlineParsingException("Illegal number of entries: " + entries.size(), cmdConfig);
    }
    Map.Entry<String, Object> entry = entries.iterator().next();
    String cmdName = entry.getKey();
    
    Class cmdClass;
    LOG.trace("cmdName: {}", cmdName);
    if (!cmdName.contains(".") && !cmdName.contains("/")) {
      cmdClass = getContext().getCommandBuilder(cmdName);
      if (cmdClass == null) {
        throw new MorphlineParsingException("No command builder registered for name: " + cmdName, cmdConfig);
      }
    } else {
      String className = cmdName.replace('/', '.');
      try {
        cmdClass = Class.forName(className);
      } catch (ClassNotFoundException e) {
        throw new MorphlineParsingException("Cannot find command class: " + className, cmdConfig, e);
      }
    }
    Object obj;
    try {
      obj = cmdClass.newInstance();
    } catch (Exception e) {
      throw new MorphlineParsingException("Cannot instantiate command class: " + cmdClass.getName(), cmdConfig, e);
    }
    if (!(obj instanceof CommandBuilder)) {
      throw new MorphlineParsingException("Type of command " + cmdName + " must be an instance of "
          + CommandBuilder.class.getName() + " but is: " + cmdClass.getName(), cmdConfig);
    } 
    //LOG.info("cmdConfigEntry {}", cmdConfig.entrySet().iterator().next());
//    LOG.info("cmdConfigConvertToAndBack {}", cmdConfig.root().entrySet().iterator().next().getValue().
//    .toConfig().getConfig(cmdClass));      
//    cmdConfig.getValue("").
//    getString(cmdClass);
    CommandBuilder builder = (CommandBuilder) obj;
    Command cmd = builder.build(cmdConfig.getConfig(cmdName), currentParent, finalChild, getContext());
    return cmd;
  }
  
  protected static Morphline getMorphline(Command p) {
    while (!(p instanceof Morphline)) {
      p = p.getParent();
    }
    Preconditions.checkNotNull(p);
    return (Morphline) p;
  }

}
