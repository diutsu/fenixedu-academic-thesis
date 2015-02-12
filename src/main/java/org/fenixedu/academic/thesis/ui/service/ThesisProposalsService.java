package org.fenixedu.academic.thesis.ui.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.fenixedu.academic.domain.Degree;
import org.fenixedu.academic.domain.ExecutionDegree;
import org.fenixedu.academic.domain.ExecutionYear;
import org.fenixedu.academic.domain.Teacher;
import org.fenixedu.academic.domain.accessControl.CoordinatorGroup;
import org.fenixedu.academic.domain.exceptions.DomainException;
import org.fenixedu.academic.domain.student.Registration;
import org.fenixedu.academic.domain.util.email.Message;
import org.fenixedu.academic.thesis.domain.StudentThesisCandidacy;
import org.fenixedu.academic.thesis.domain.ThesisProposal;
import org.fenixedu.academic.thesis.domain.ThesisProposalParticipant;
import org.fenixedu.academic.thesis.domain.ThesisProposalParticipantType;
import org.fenixedu.academic.thesis.domain.ThesisProposalsConfiguration;
import org.fenixedu.academic.thesis.domain.ThesisProposalsSystem;
import org.fenixedu.academic.thesis.ui.bean.ThesisProposalBean;
import org.fenixedu.academic.thesis.ui.bean.ThesisProposalParticipantBean;
import org.fenixedu.academic.thesis.ui.exception.CannotEditUsedThesisProposalsException;
import org.fenixedu.academic.thesis.ui.exception.IllegalParticipantTypeException;
import org.fenixedu.academic.thesis.ui.exception.InvalidPercentageException;
import org.fenixedu.academic.thesis.ui.exception.MaxNumberThesisProposalsException;
import org.fenixedu.academic.thesis.ui.exception.OutOfProposalPeriodException;
import org.fenixedu.academic.thesis.ui.exception.ThesisProposalException;
import org.fenixedu.academic.thesis.ui.exception.TotalParticipantPercentageException;
import org.fenixedu.academic.thesis.ui.exception.UnequivalentThesisConfigurationsException;
import org.fenixedu.academic.thesis.ui.exception.UnexistentThesisParticipantException;
import org.fenixedu.bennu.core.domain.Bennu;
import org.fenixedu.bennu.core.domain.User;
import org.fenixedu.bennu.core.groups.DynamicGroup;
import org.fenixedu.bennu.core.i18n.BundleUtil;
import org.fenixedu.bennu.core.security.Authenticate;
import org.fenixedu.bennu.core.util.CoreConfiguration;
import org.fenixedu.bennu.signals.DomainObjectEvent;
import org.fenixedu.bennu.signals.Signal;
import org.fenixedu.commons.i18n.I18N;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import pt.ist.fenixframework.Atomic;
import pt.ist.fenixframework.Atomic.TxMode;
import pt.ist.fenixframework.FenixFramework;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@Service
public class ThesisProposalsService {

    private static final Logger logger = LoggerFactory.getLogger(ThesisProposalsService.class);
    static String BUNDLE = "resources.FenixEduThesisProposalsResources";

    @Autowired
    MessageSource messageSource;

    public List<ThesisProposal> getCoordinatorProposals(ThesisProposalsConfiguration configuration) {
        return getCoordinatorProposals(configuration, null, null, null);
    }

    public List<ThesisProposal> getCoordinatorProposals(ThesisProposalsConfiguration configuration, Boolean isVisible,
            Boolean isAttributed, Boolean hasCandidacy) {

        if (configuration == null) {
            return new ArrayList<ThesisProposal>();
        }

        Stream<ThesisProposal> proposalsStream = configuration.getThesisProposalSet().stream();

        if (isVisible != null) {
            Predicate<ThesisProposal> visiblePredicate = proposal -> isVisible.equals(!proposal.getHidden());
            proposalsStream = proposalsStream.filter(visiblePredicate);
        }

        if (hasCandidacy != null) {
            Predicate<ThesisProposal> hasCandidacyPredicate =
                    proposal -> hasCandidacy ? proposal.getStudentThesisCandidacySet().size() > 0 : proposal
                            .getStudentThesisCandidacySet().size() == 0;

            proposalsStream = proposalsStream.filter(hasCandidacyPredicate);
        }

        if (isAttributed != null) {
            Predicate<ThesisProposal> attributedPredicate =
                    isAttributed ? p -> p.getStudentThesisCandidacySet().stream()
                            .anyMatch(StudentThesisCandidacy::getAcceptedByAdvisor) : p -> p.getStudentThesisCandidacySet()
                            .stream().noneMatch(StudentThesisCandidacy::getAcceptedByAdvisor);

            proposalsStream = proposalsStream.filter(attributedPredicate);
        }

        return proposalsStream.collect(Collectors.toList());
    }

    public List<ThesisProposal> getThesisProposals(User user, ExecutionYear year) {
        return getThesisProposalsConfigurations(user)
                .stream()
                .filter(p -> p.getExecutionDegree().getExecutionYear().equals(year))
                .flatMap(configuration -> configuration.getThesisProposalSet().stream())
                .distinct()
                .filter(proposal -> proposal.getThesisProposalParticipantSet().stream()
                        .anyMatch(participant -> participant.getUser().equals(user)))
                .sorted(ThesisProposal.COMPARATOR_BY_NUMBER_OF_CANDIDACIES).collect(Collectors.toList());
    }

    public List<ThesisProposal> getThesisProposals(User user, ThesisProposalsConfiguration configuration) {
        return ThesisProposal.readProposalsByUserAndConfiguration(user, configuration).stream()
                .sorted(ThesisProposal.COMPARATOR_BY_NUMBER_OF_CANDIDACIES).collect(Collectors.toList());
    }

    public List<ThesisProposalsConfiguration> getThesisProposalsConfigurations(User user) {

        Objects.nonNull(user);
        Objects.nonNull(user.getPerson());

        final Teacher teacher = user.getPerson().getTeacher();

        if (teacher == null) {
            return new ArrayList<>();
        }

        Stream<ThesisProposalsConfiguration> configurationsForAuthorizations =
                teacher.getTeacherAuthorizationStream().flatMap(auth -> auth.getDepartment().getDegreesSet().stream())
                        .flatMap(degree -> degree.getExecutionDegrees().stream())
                        .flatMap(executionDegree -> executionDegree.getThesisProposalsConfigurationSet().stream()).distinct();

        Stream<ThesisProposalsConfiguration> configurationsForParticipants =
                user.getThesisProposalParticipantSet().stream()
                        .flatMap(participant -> participant.getThesisProposal().getThesisConfigurationSet().stream()).distinct();

        return Stream.concat(configurationsForAuthorizations, configurationsForParticipants).distinct()
                .sorted(ThesisProposalsConfiguration.COMPARATOR_BY_PROPOSAL_PERIOD_START_DESC).collect(Collectors.toList());
    }

    public List<ThesisProposalsConfiguration> getThesisProposalsConfigurationsForCoordinator(User coordinator) {
        return Degree.readBolonhaDegrees().stream().flatMap(degree -> degree.getExecutionDegrees().stream())
                .filter(executionDegree -> CoordinatorGroup.get(executionDegree.getDegree()).isMember(coordinator))
                .flatMap(executionDegree -> executionDegree.getThesisProposalsConfigurationSet().stream()).distinct()
                .sorted(ThesisProposalsConfiguration.COMPARATOR_BY_PROPOSAL_PERIOD_START_DESC).collect(Collectors.toList());
    }

    @Atomic(mode = TxMode.WRITE)
    public ThesisProposal createThesisProposal(ThesisProposalBean proposalBean, String participantsJson)
            throws ThesisProposalException {

        JsonParser parser = new JsonParser();
        JsonArray jsonArray = parser.parse(participantsJson).getAsJsonArray();

        Set<ThesisProposalParticipantBean> participants = new HashSet<ThesisProposalParticipantBean>();

        for (JsonElement elem : jsonArray) {
            JsonObject jsonObj = elem.getAsJsonObject();

            String userId = jsonObj.get("userId").getAsString();
            String userType = jsonObj.get("userType").getAsString();
            int percentage = jsonObj.get("percentage").getAsInt();

            if (percentage > 100 || percentage < 0) {
                throw new InvalidPercentageException(percentage);
            }

            if (userType.isEmpty()) {
                throw new IllegalParticipantTypeException(User.findByUsername(userId));
            }

            if (userId == null || userId.isEmpty()) {
                throw new UnexistentThesisParticipantException();
            }

            participants.add(new ThesisProposalParticipantBean(User.findByUsername(userId), userType, percentage));
        }

        if (participants.isEmpty()) {
            throw new UnexistentThesisParticipantException();
        }

        proposalBean.setThesisProposalParticipantsBean(participants);
        ThesisProposal thesisProposal = new ThesisProposalBean.Builder(proposalBean).build();
        Signal.emit(ThesisProposal.SIGNAL_CREATED, new DomainObjectEvent<ThesisProposal>(thesisProposal));
        return thesisProposal;
    }

    @Atomic(mode = TxMode.WRITE)
    public boolean delete(ThesisProposal thesisProposal) {
        try {
            thesisProposal.delete();
        } catch (DomainException domainException) {
            return false;
        }
        return true;
    }

    public Map<String, StudentThesisCandidacy> getBestAccepted(ThesisProposal thesisProposal) {
        HashMap<String, StudentThesisCandidacy> bestAccepted = new HashMap<String, StudentThesisCandidacy>();

        for (StudentThesisCandidacy candidacy : thesisProposal.getStudentThesisCandidacySet()) {
            Registration registration = candidacy.getRegistration();
            if (!bestAccepted.containsKey(registration.getExternalId())) {
                for (StudentThesisCandidacy studentCandidacy : registration.getStudentThesisCandidacySet()) {
                    if (studentCandidacy.getAcceptedByAdvisor()
                            && (!bestAccepted.containsKey(registration.getExternalId()) || studentCandidacy.getPreferenceNumber() < bestAccepted
                                    .get(registration.getExternalId()).getPreferenceNumber())) {
                        bestAccepted.put(registration.getExternalId(), studentCandidacy);
                    }
                }
            }
        }
        return bestAccepted;
    }

    @Atomic(mode = TxMode.WRITE)
    public void editThesisProposal(User currentUser, ThesisProposalBean thesisProposalBean, ThesisProposal thesisProposal,
            JsonArray jsonArray) throws ThesisProposalException {

        boolean isManager = DynamicGroup.get("managers").isMember(currentUser);
        boolean isDegreeCoordinator =
                thesisProposal.getExecutionDegreeSet().stream()
                        .anyMatch(execDegree -> CoordinatorGroup.get(execDegree.getDegree()).isMember(currentUser));

        if (!(isManager || isDegreeCoordinator || thesisProposal.getStudentThesisCandidacySet().isEmpty())) {
            throw new CannotEditUsedThesisProposalsException(thesisProposal);
        }

        ArrayList<ThesisProposalParticipantBean> participantsBean = new ArrayList<ThesisProposalParticipantBean>();

        for (JsonElement elem : jsonArray) {
            JsonObject jsonObj = elem.getAsJsonObject();

            String userId = jsonObj.get("userId").getAsString();
            String userType = jsonObj.get("userType").getAsString();
            int percentage = jsonObj.get("percentage").getAsInt();

            if (percentage > 100 || percentage < 0) {
                throw new InvalidPercentageException(percentage);
            }

            if (userType.isEmpty()) {
                throw new IllegalParticipantTypeException(User.findByUsername(userId));
            }

            ThesisProposalParticipantBean participantBean =
                    new ThesisProposalParticipantBean(User.findByUsername(userId), userType, percentage);

            participantsBean.add(participantBean);
        }

        if (participantsBean.isEmpty()) {
            throw new UnexistentThesisParticipantException();
        }
        for (ThesisProposalParticipant participant : thesisProposal.getThesisProposalParticipantSet()) {
            participant.delete();
        }

        thesisProposal.getThesisProposalParticipantSet().clear();

        ArrayList<ThesisProposalParticipant> participants = new ArrayList<ThesisProposalParticipant>();

        int totalPercentage =
                participantsBean.stream().map(ThesisProposalParticipantBean::getPercentage).reduce(0, (a, b) -> a + b);
        if (totalPercentage != 100) {
            throw new TotalParticipantPercentageException();
        }

        for (ThesisProposalParticipantBean participantBean : participantsBean) {
            User user = FenixFramework.getDomainObject(participantBean.getUserExternalId());

            ThesisProposalParticipantType participantType =
                    FenixFramework.getDomainObject(participantBean.getParticipantTypeExternalId());

            ThesisProposalParticipant participant =
                    new ThesisProposalParticipant(user, participantType, participantBean.getPercentage());

            for (ThesisProposalsConfiguration configuration : thesisProposal.getThesisConfigurationSet()) {
                int proposalsCount =
                        configuration
                                .getThesisProposalSet()
                                .stream()
                                .filter(proposal -> proposal.getThesisProposalParticipantSet().stream().map(p -> p.getUser())
                                        .collect(Collectors.toSet()).contains(participant.getUser())).collect(Collectors.toSet())
                                .size();

                if (!(isManager || isDegreeCoordinator) && configuration.getMaxThesisProposalsByUser() != -1
                        && proposalsCount >= configuration.getMaxThesisProposalsByUser()) {
                    throw new MaxNumberThesisProposalsException(participant);
                }

                else {
                    participant.setThesisProposal(thesisProposal);
                    participants.add(participant);
                }
            }
        }

        thesisProposal.setTitle(thesisProposalBean.getTitle());
        thesisProposal.setObservations(thesisProposalBean.getObservations());
        thesisProposal.setRequirements(thesisProposalBean.getRequirements());
        thesisProposal.setGoals(thesisProposalBean.getGoals());
        thesisProposal.getThesisConfigurationSet().clear();
        thesisProposal.getThesisConfigurationSet().addAll(thesisProposalBean.getThesisProposalsConfigurations());

        thesisProposal.getThesisProposalParticipantSet().addAll(participants);

        ThesisProposalsConfiguration base =
                (ThesisProposalsConfiguration) thesisProposalBean.getThesisProposalsConfigurations().toArray()[0];

        for (ThesisProposalsConfiguration configuration : thesisProposalBean.getThesisProposalsConfigurations()) {
            if (!base.isEquivalent(configuration)) {
                throw new UnequivalentThesisConfigurationsException(base, configuration);
            }
        }

        ThesisProposalsConfiguration config = thesisProposal.getSingleThesisProposalsConfiguration();

        if (!(isManager || isDegreeCoordinator) && !config.getProposalPeriod().containsNow()) {
            throw new OutOfProposalPeriodException();
        }
        thesisProposal.setLocalization(thesisProposalBean.getLocalization());
    }

    @Atomic(mode = TxMode.WRITE)
    public void accept(StudentThesisCandidacy studentThesisCandidacy) {
        final ThesisProposal thesisProposal = studentThesisCandidacy.getThesisProposal();

        for (StudentThesisCandidacy candidacy : thesisProposal.getStudentThesisCandidacySet()) {
            candidacy.setAcceptedByAdvisor(false);
        }

        studentThesisCandidacy.setAcceptedByAdvisor(true);

        int orderOfPreference = studentThesisCandidacy.getPreferenceNumber();

        final Registration registration = studentThesisCandidacy.getRegistration();

        Optional<StudentThesisCandidacy> max =
                registration
                        .getStudentThesisCandidacySet()
                        .stream()
                        .filter(candidacy -> candidacy.getAcceptedByAdvisor()
                                && candidacy.getPreferenceNumber() > orderOfPreference)
                        .max(StudentThesisCandidacy.COMPARATOR_BY_PREFERENCE_NUMBER);

        if (max.isPresent()) {
            sendStolenProposalMessage(max.get(), studentThesisCandidacy);
        }
    }

    @Atomic(mode = TxMode.WRITE)
    public void revoke(StudentThesisCandidacy studentThesisCandidacy) {
        studentThesisCandidacy.setAcceptedByAdvisor(false);
    }

    private Optional<String> getAuthenticateGetUserName() {
        String name = null;
        if (Authenticate.getUser() != null) {
            name = Authenticate.getUser().getProfile().getDisplayName();
        }
        return Optional.ofNullable(name);
    }

    private void sendStolenProposalMessage(StudentThesisCandidacy oldCandidacy, StudentThesisCandidacy newCandidacy) {

        ThesisProposalParticipant newParticipant =
                newCandidacy.getThesisProposal().getThesisProposalParticipantSet().stream()
                        .max(ThesisProposalParticipant.COMPARATOR_BY_WEIGHT).get();

        Set<String> bccs =
                oldCandidacy.getThesisProposal().getThesisProposalParticipantSet().stream()
                        .map(p -> p.getUser().getProfile().getEmail()).collect(Collectors.toSet());

        String link =
                CoreConfiguration.getConfiguration().applicationUrl() + "/proposals/manage/"
                        + oldCandidacy.getThesisProposal().getExternalId();

        String subject =
                messageSource.getMessage("stolen.proposal.message.subject", new Object[] { oldCandidacy.getThesisProposal()
                        .getIdentifier() }, I18N.getLocale());

        String body =
                messageSource.getMessage("stolen.proposal.message.body", new Object[] {
                        oldCandidacy.getRegistration().getStudent().getPerson().getUser().getProfile().getDisplayName(),
                        oldCandidacy.getThesisProposal().getTitle(), oldCandidacy.getPreferenceNumber(),
                        newCandidacy.getThesisProposal().getTitle(), newParticipant.getUser().getProfile().getDisplayName(),
                        newCandidacy.getPreferenceNumber(), link, getAuthenticateGetUserName().orElse("System") },
                        I18N.getLocale());

        new Message(Bennu.getInstance().getSystemSender(), null, null, subject, body, bccs);
    }

    @Atomic(mode = TxMode.WRITE)
    public void reject(StudentThesisCandidacy studentThesisCandidacy) {
        studentThesisCandidacy.setAcceptedByAdvisor(false);
    }

    public List<ThesisProposalParticipantType> getThesisProposalParticipantTypes() {
        return ThesisProposalsSystem.getInstance().getThesisProposalParticipantTypeSet().stream()
                .sorted(ThesisProposalParticipantType.COMPARATOR_BY_WEIGHT).distinct().collect(Collectors.toList());
    }

    public List<ThesisProposalsConfiguration> getCurrentThesisProposalsConfigurations() {
        return getCurrentThesisProposalsConfigurations(null);
    }

    public List<ThesisProposalsConfiguration> getCurrentThesisProposalsConfigurations(
            Comparator<ThesisProposalsConfiguration> comparator) {
        return getCurrentConfigurations(comparator).distinct().collect(Collectors.toList());
    }

    private Stream<ThesisProposalsConfiguration> getCurrentConfigurations(Comparator<ThesisProposalsConfiguration> comparator) {
        Stream<ThesisProposalsConfiguration> configurations =
                ThesisProposalsSystem.getInstance().getThesisProposalsConfigurationSet().stream()
                        .filter(config -> config.getProposalPeriod().containsNow());

        if (comparator != null) {
            configurations = configurations.sorted(comparator);
        }

        return configurations;

    }

    public List<StudentThesisCandidacy> getStudentThesisCandidacy(ThesisProposal thesisProposal) {
        return thesisProposal.getStudentThesisCandidacySet().stream().sorted(StudentThesisCandidacy.COMPARATOR_BY_DATETIME)
                .distinct().collect(Collectors.toList());

    }

    public Set<ThesisProposal> getRecentProposals(User user) {
        Set<ThesisProposal> proposals =
                user.getThesisProposalParticipantSet().stream().map(participant -> participant.getThesisProposal())
                        .collect(Collectors.toSet());

        HashMap<String, Set<ThesisProposal>> proposalTitleMap = new HashMap<String, Set<ThesisProposal>>();

        for (ThesisProposal proposal : proposals) {
            if (!proposalTitleMap.containsKey(proposal.getTitle())) {
                proposalTitleMap.put(proposal.getTitle(), new HashSet<ThesisProposal>());
            }
            proposalTitleMap.get(proposal.getTitle()).add(proposal);
        }

        Set<ThesisProposal> recentProposals = new HashSet<ThesisProposal>();
        for (String key : proposalTitleMap.keySet()) {
            recentProposals.add(proposalTitleMap.get(key).stream().max(ThesisProposal.COMPARATOR_BY_PROPOSAL_PERIOD).get());
        }
        return recentProposals;
    }

    public Map<Registration, TreeSet<StudentThesisCandidacy>> getCoordinatorCandidacies(ThesisProposalsConfiguration configuration) {

        Map<Registration, TreeSet<StudentThesisCandidacy>> map = new HashMap<Registration, TreeSet<StudentThesisCandidacy>>();

        configuration
                .getThesisProposalSet()
                .stream()
                .flatMap(proposal -> proposal.getStudentThesisCandidacySet().stream())
                .collect(Collectors.toSet())
                .forEach(
                        candidacy -> {
                            if (!map.containsKey(candidacy.getRegistration())) {
                                map.put(candidacy.getRegistration(), new TreeSet<StudentThesisCandidacy>(
                                        StudentThesisCandidacy.COMPARATOR_BY_PREFERENCE_NUMBER));
                            }
                            map.get(candidacy.getRegistration()).add(candidacy);
                        });
        return map;
    }

    public String[] getThesisProposalDegrees(ThesisProposal proposal) {
        return proposal.getExecutionDegreeSet().stream().map(executionDegree -> executionDegree.getDegree().getSigla())
                .collect(Collectors.toList()).toArray(new String[0]);
    }

    public String[] getThesisProposalCandidates(ThesisProposal proposal) {
        return proposal
                .getStudentThesisCandidacySet()
                .stream()
                .map(candidacy -> {
                    User user = candidacy.getRegistration().getStudent().getPerson().getUser();
                    return user.getProfile().getDisplayName() + " (" + user.getUsername() + ") - "
                            + BundleUtil.getString(BUNDLE, "label.preference.number") + ": " + candidacy.getPreferenceNumber();
                }).collect(Collectors.toList()).toArray(new String[0]);
    }

    public boolean isAccepted(ThesisProposal proposal) {
        return proposal.getStudentThesisCandidacySet().stream().anyMatch(StudentThesisCandidacy::getAcceptedByAdvisor);
    }

    public boolean canTeacherAcceptedCandidacy(final ThesisProposal proposal) {
        return proposal
                .getStudentThesisCandidacySet()
                .stream()
                .anyMatch(candidacy -> {
                    //best accepted for that student
                        Optional<StudentThesisCandidacy> hit =
                                candidacy.getRegistration().getStudentThesisCandidacySet().stream()
                                        .filter(StudentThesisCandidacy::getAcceptedByAdvisor)
                                        .min(StudentThesisCandidacy.COMPARATOR_BY_PREFERENCE_NUMBER);

                        if (!hit.isPresent()
                                || (hit.isPresent() && hit.get().getPreferenceNumber() > candidacy.getPreferenceNumber())) {
                            return true;
                        } else {
                            return false;
                        }
                    });
    }

    @Atomic(mode = TxMode.WRITE)
    public boolean toggleVisibility(ThesisProposal proposal) {
        final boolean state = !proposal.getHidden();
        proposal.setHidden(state);
        return state;
    }

    public List<ExecutionYear> getThesisProposalsConfigurationsExecutionYears(User user) {
        return getThesisProposalsConfigurations(user).stream().map(ThesisProposalsConfiguration::getExecutionDegree)
                .map(ExecutionDegree::getExecutionYear).distinct().sorted(ExecutionYear.COMPARATOR_BY_YEAR.reversed())
                .collect(Collectors.toList());
    }
}
