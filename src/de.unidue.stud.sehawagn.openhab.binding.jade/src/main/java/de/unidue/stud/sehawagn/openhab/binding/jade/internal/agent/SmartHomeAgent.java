package de.unidue.stud.sehawagn.openhab.binding.jade.internal.agent;

import java.util.Observable;
import java.util.Observer;

import agentgui.simulationService.SimulationService;
import agentgui.simulationService.time.TimeModelContinuous;
import de.unidue.stud.sehawagn.openhab.binding.jade.handler.SmartHomeAgentHandler;
import energy.optionModel.ScheduleList;
import energy.optionModel.TechnicalSystem;
import energy.optionModel.TechnicalSystemGroup;
import hygrid.agent.AbstractEnergyAgent;
import hygrid.agent.AbstractInternalDataModel;
import hygrid.agent.ControlBehaviourRT;
import hygrid.agent.EnergyAgentIO;
import hygrid.agent.SimulationConnectorRemote;
import hygrid.agent.SimulationConnectorRemoteForIOReal;
import hygrid.agent.monitoring.MonitoringBehaviourRT;
import hygrid.agent.monitoring.MonitoringListenerForLogging;
import hygrid.agent.monitoring.MonitoringListenerForProxy;
import hygrid.env.agentConfig.controller.AgentConfigController;
import hygrid.env.agentConfig.dataModel.AgentConfig;
import hygrid.env.agentConfig.dataModel.AgentOperatingMode;
import hygrid.ontology.HyGridOntology;
import jade.content.lang.sl.SLCodec;
import jade.core.AID;
import jade.core.ServiceException;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * An energy agent, representing a "smart home", using the configured EOM.
 */
public class SmartHomeAgent extends AbstractEnergyAgent implements Observer {

	private static final long serialVersionUID = 1730951019391324601L;
	private AgentOperatingMode operatingMode;
	private AgentConfigController agentConfigController;

	private EnergyAgentIO agentIOBehaviour;
	private InternalDataModel internalDataModel;
	private ControlBehaviourRT controlBehaviourRT;
	private MonitoringBehaviourRT monitoringBehaviourRT;

	private SmartHomeAgentHandler myAgentHandler;

	private MessageTemplate messageTemplate;
	private SimulationConnectorRemote simulationConnector;

	/*
	 * @see jade.core.Agent#setup()
	 */
	@Override
	protected void setup() {
		Object[] args = this.getArguments();
		if (args != null) { // if started by the simulation environment
			if (args.length >= 1 && args[0] instanceof AgentConfig) {
				getAgentConfigController().setAgentConfig((AgentConfig) args[0]);
			}
			if (args.length >= 2 && args[1] instanceof SmartHomeAgentHandler) {
				myAgentHandler = (SmartHomeAgentHandler) args[1];
			}
		}

		// prepare communication with other platforms
		this.getContentManager().registerLanguage(new SLCodec());
		this.getContentManager().registerOntology(HyGridOntology.getInstance());

		// If in testbed messages from the central agent are handled by a different behaviour
		AgentOperatingMode operatingMode = this.getAgentOperatingMode();

		if (operatingMode == AgentOperatingMode.TestBedSimulation || operatingMode == AgentOperatingMode.TestBedReal
				|| operatingMode == AgentOperatingMode.RealSystem) {
			AID centralAgent = this.getAgentConfigController().getCentralAgentAID();
			this.messageTemplate = MessageTemplate.not(MessageTemplate.MatchSender(centralAgent));
		}

		// initialize SimulationConnector
		if (operatingMode == AgentOperatingMode.TestBedReal || operatingMode == AgentOperatingMode.RealSystem) {
			this.simulationConnector = new SimulationConnectorRemoteForIOReal(this, this.getInternalDataModel());
		}

		Behaviour ioBehaviour = (Behaviour) this.getAgentIOBehaviour();
		if (ioBehaviour != null) {
			this.addBehaviour(ioBehaviour);
		}
		this.addBehaviour(new MessageReceiveBehaviour()); // additional behaviours
		System.out.println("SmartHomeAgent started");
	}

	/*
	 * @see jade.core.Agent#takeDown()
	 */
	@Override
	protected void takeDown() {
		if (getAgentOperatingMode() != null) {
			switch (this.getAgentOperatingMode()) {
			case Simulation:
				if (this.agentIOBehaviour != null) {
					((SimulatedIOBehaviour) this.agentIOBehaviour).stopTimeTriggerForSystemInput();
				}
				break;

			case TestBedSimulation:
				break;

			case TestBedReal:
				break;

			case RealSystem:
				break;
			default:
				break;
			}
		}

	}

	/**
	 * @return the agent config controller
	 */
	@Override
	public AgentConfigController getAgentConfigController() {
		if (agentConfigController == null) {
			agentConfigController = new AgentConfigController();
		}
		return agentConfigController;
	}

	/**
	 * @return the current {@link AgentOperatingMode}.
	 */
	public AgentOperatingMode getAgentOperatingMode() {
		if (operatingMode == null) {
			if (this.isSimulation() == true) {
				operatingMode = AgentOperatingMode.Simulation;
			} else {
				// Possible other cases:
				// TestBedSimulation
				// TestBedReal
				// RealSystem

				AgentConfig agentConfig = this.getAgentConfigController().getAgentConfig();
				operatingMode = agentConfig.getAgentOperatingMode();
			}
		}
		return operatingMode;
	}

	/**
	 * @return true, if the execution is simulation
	 */
	private boolean isSimulation() {
		try {
			this.getHelper(SimulationService.NAME);
			return true;
		} catch (ServiceException se) {
			// mute exception because it only determines that no simulation is running
		}
		return false;
	}

	/**
	 * @return the internal data model of this agent
	 */
	public InternalDataModel getInternalDataModel() {
		if (this.internalDataModel == null) {
			this.internalDataModel = new InternalDataModel(this);
			// necessary to initialize the datamodel's controlledSystemType
			this.internalDataModel.getOptionModelController();
			this.internalDataModel.addObserver(this);
		}
		return this.internalDataModel;
	}

	/**
	 * @return the current IO behaviour
	 */
	public EnergyAgentIO getAgentIOBehaviour() {
		if (agentIOBehaviour == null) {
			if (getAgentOperatingMode() != null) {

				switch (this.getAgentOperatingMode()) {
				case Simulation: {
					agentIOBehaviour = new SimulatedIOBehaviour(this, this.getInternalDataModel());
					break;
				}
				case TestBedSimulation: {
					agentIOBehaviour = new SimulatedIOBehaviour(this, this.getInternalDataModel());
					break;
				}
				case TestBedReal: {
					// TODO
					break;
				}
				case RealSystem: {
					agentIOBehaviour = new RealIOBehaviour(this, myAgentHandler);

					if (this.simulationConnector.getEnvironmentModel() != null) {
						// Determine simulation start time
						TimeModelContinuous timeModel = (TimeModelContinuous) this.simulationConnector.getEnvironmentModel().getTimeModel();
						((RealIOBehaviour) agentIOBehaviour).setSimulationStartTime(timeModel.getTimeStart());
						this.startMonitoringBehaviourRT();
					}
					break;
				}
				default: {
					break;
				}
				}
			}
		}
		return agentIOBehaviour;
	}

	/**
	 * Start real time control behaviour ControlBehaviourRT, if not already done.
	 */
	private void startControlBehaviourRT() {
		if (controlBehaviourRT == null) {
			controlBehaviourRT = new ControlBehaviourRT(this, this.getInternalDataModel(), this.getAgentIOBehaviour());
			this.addBehaviour(controlBehaviourRT);
		}
	}

	/**
	 * Start real time control behaviour MonitoringBehaviourRT, if not already done.
	 */
	private void startMonitoringBehaviourRT() {
		if (monitoringBehaviourRT == null) {
			monitoringBehaviourRT = new MonitoringBehaviourRT(this.getInternalDataModel(), this.getAgentIOBehaviour());
			monitoringBehaviourRT.addMonitoringListener(new MonitoringListenerForLogging());
			monitoringBehaviourRT.addMonitoringListener(new MonitoringListenerForProxy(this.simulationConnector));
			this.getInternalDataModel().addObserver(monitoringBehaviourRT);
			this.addBehaviour(monitoringBehaviourRT);
		}
	}

	/*
	 * Will be invoked if the internal data model has changed
	 *
	 * @see java.util.Observer#update(java.util.Observable, java.lang.Object)
	 */
	@Override
	public void update(Observable observable, Object updateObject) {
		if (observable instanceof InternalDataModel) {
			if (updateObject == AbstractInternalDataModel.CHANGED.NetworModel) {

			} else if (updateObject == AbstractInternalDataModel.CHANGED.NetworkComponent) {
				// Get the actual data model of the NetworkComponent
				Object dm = this.getInternalDataModel().getNetworkComponent().getDataModel();
				if (dm instanceof ScheduleList) {
					this.internalDataModel.getScheduleController().setScheduleList((ScheduleList) dm);

				} else if (dm instanceof TechnicalSystem) {
					this.internalDataModel.getOptionModelController().setTechnicalSystem((TechnicalSystem) dm);
					if (this.internalDataModel.getOptionModelController().getEvaluationStrategyRT() != null) {
						this.startControlBehaviourRT(); // Add real time control, if configured

					}

				} else if (dm instanceof TechnicalSystemGroup) {
					this.internalDataModel.getGroupController().setTechnicalSystemGroup((TechnicalSystemGroup) dm);
					if (this.internalDataModel.getOptionModelController().getEvaluationStrategyRT() != null) {
						this.startControlBehaviourRT(); // add real time control, if configured
					}

				}

			} else if (updateObject == AbstractInternalDataModel.CHANGED.MeasurementsFromSystem) {
			}
		}
	}

	public MessageTemplate getMessageTemplate() {
		return messageTemplate;
	}

	public void setMessageTemplate(MessageTemplate messageTemplate) {
		this.messageTemplate = messageTemplate;
	}

	/**
	 * Internal class for message handling
	 */
	private class MessageReceiveBehaviour extends CyclicBehaviour {

		private static final long serialVersionUID = -6383794735800175272L;

		@Override
		public void action() {
			ACLMessage msg = this.myAgent.receive(getMessageTemplate());
			if (msg != null) {
				// --- work on the delivered message --------

			} else {
				// --- wait for the next incoming message ---
				block();
			}
		}
	}
}
