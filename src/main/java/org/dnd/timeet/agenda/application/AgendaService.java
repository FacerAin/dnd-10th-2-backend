package org.dnd.timeet.agenda.application;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.dnd.timeet.agenda.domain.Agenda;
import org.dnd.timeet.agenda.domain.AgendaAction;
import org.dnd.timeet.agenda.domain.AgendaRepository;
import org.dnd.timeet.agenda.domain.AgendaStatus;
import org.dnd.timeet.agenda.dto.AgendaActionRequest;
import org.dnd.timeet.agenda.dto.AgendaActionResponse;
import org.dnd.timeet.agenda.dto.AgendaCreateRequest;
import org.dnd.timeet.agenda.dto.AgendaInfoResponse;
import org.dnd.timeet.common.exception.BadRequestError;
import org.dnd.timeet.common.exception.NotFoundError;
import org.dnd.timeet.common.exception.NotFoundError.ErrorCode;
import org.dnd.timeet.common.utils.DurationUtils;
import org.dnd.timeet.meeting.domain.Meeting;
import org.dnd.timeet.meeting.domain.MeetingRepository;
import org.dnd.timeet.member.domain.Member;
import org.dnd.timeet.participant.domain.ParticipantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor // final 의존성 주입
@Transactional // DB 변경 작업에 사용
public class AgendaService {

    private final MeetingRepository meetingRepository;
    private final AgendaRepository agendaRepository;
    private final ParticipantRepository participantRepository;

    @Transactional
    public Long createAgenda(Long meetingId, AgendaCreateRequest createDto, Member member) {
        Meeting meeting = meetingRepository.findById(meetingId)
            .orElseThrow(() -> new NotFoundError(ErrorCode.RESOURCE_NOT_FOUND,
                Collections.singletonMap("MeetingId", "Meeting not found")));

        // 회의에 참가한 멤버인지 확인
        participantRepository.findByMeetingIdAndMemberId(meetingId, member.getId())
            .orElseThrow(() -> new BadRequestError(BadRequestError.ErrorCode.VALIDATION_FAILED,
                Collections.singletonMap("MemberId", "Member is not a participant of the meeting")));

        Agenda agenda = createDto.toEntity(meeting);
        List<Agenda> agendaList = agendaRepository.findByMeetingId(meetingId);
        agenda.setOrderNum(agendaList.size() + 1);
        agenda = agendaRepository.save(agenda);

        // 회의 시간 추가
        addMeetingTotalActualDuration(meetingId,
            DurationUtils.convertLocalTimeToDuration(createDto.getAllocatedDuration()));

        return agenda.getId();
    }

    @Transactional(readOnly = true)
    public List<Agenda> findAll(Long meetingId) {
        return agendaRepository.findByMeetingId(meetingId);
    }

    public AgendaActionResponse changeAgendaStatus(Long meetingId, Long agendaId, AgendaActionRequest actionRequest) {
        Agenda agenda = agendaRepository.findByIdAndMeetingId(agendaId, meetingId)
            .orElseThrow(() -> new NotFoundError(ErrorCode.RESOURCE_NOT_FOUND,
                Collections.singletonMap("AgendaId", "Agenda not found")));

        String actionString = actionRequest.getAction().toUpperCase();
        AgendaAction action;

        try {
            action = AgendaAction.valueOf(actionString);
        } catch (IllegalArgumentException e) {
            throw new BadRequestError(BadRequestError.ErrorCode.VALIDATION_FAILED,
                Collections.singletonMap("Action", "Invalid action"));
        }

        switch (action) {
            case START:
                agenda.start();
                break;
            case PAUSE:
                agenda.pause();
                break;
            case RESUME:
                agenda.resume();
                break;
            case END:
                agenda.complete();
                break;
            case MODIFY:
                LocalTime modifiedDuration = LocalTime.parse(actionRequest.getModifiedDuration());
                Duration duration = DurationUtils.convertLocalTimeToDuration(modifiedDuration);
                agenda.extendDuration(duration);
                // 회의 시간 추가
                addMeetingTotalActualDuration(meetingId, duration);
                break;
            default:
                throw new BadRequestError(BadRequestError.ErrorCode.VALIDATION_FAILED,
                    Collections.singletonMap("Action", "Invalid action"));
        }
        Agenda savedAgenda = agendaRepository.save(agenda);

        Duration currentDuration = savedAgenda.calculateCurrentDuration();
        Duration remainingDuration = agenda.calculateRemainingTime();

        return new AgendaActionResponse(savedAgenda, currentDuration, remainingDuration);
    }

    public void cancelAgenda(Long meetingId, Long agendaId) {
        Agenda agenda = agendaRepository.findByIdAndMeetingId(agendaId, meetingId)
            .orElseThrow(() -> new NotFoundError(ErrorCode.RESOURCE_NOT_FOUND,
                Collections.singletonMap("AgendaId", "Agenda not found")));
        if (agenda.getStatus() != AgendaStatus.PENDING) {
            throw new BadRequestError(BadRequestError.ErrorCode.WRONG_REQUEST_TRANSMISSION,
                Collections.singletonMap("AgendaStatus", "Agenda is not PENDING status"));
        }
        agenda.cancel();

        subtractMeetingTotalActualDuration(meetingId, agenda.getAllocatedDuration());
    }

    public void addMeetingTotalActualDuration(Long meetingId, Duration additionalDuration) {
        Meeting meeting = meetingRepository.findById(meetingId)
            .orElseThrow(() -> new NotFoundError(ErrorCode.RESOURCE_NOT_FOUND,
                Collections.singletonMap("MeetingId", "Meeting not found")));

        Duration newTotalDuration = meeting.getTotalActualDuration().plus(additionalDuration);
        meeting.updateTotalActualDuration(newTotalDuration);
        meetingRepository.save(meeting);
    }

    public void subtractMeetingTotalActualDuration(Long meetingId, Duration subtractedDuration) {
        Meeting meeting = meetingRepository.findById(meetingId)
            .orElseThrow(() -> new NotFoundError(ErrorCode.RESOURCE_NOT_FOUND,
                Collections.singletonMap("MeetingId", "Meeting not found")));

        Duration newTotalDuration = meeting.getTotalActualDuration().minus(subtractedDuration);
        meeting.updateTotalActualDuration(newTotalDuration);
        meetingRepository.save(meeting);
    }

    public AgendaInfoResponse findAgendas(Long meetingId) {
        Meeting meeting = meetingRepository.findById(meetingId)
            .orElseThrow(() -> new NotFoundError(ErrorCode.RESOURCE_NOT_FOUND,
                Collections.singletonMap("MeetingId", "Meeting not found")));
        List<Agenda> agendaList = agendaRepository.findByMeetingId(meetingId);
        agendaList.sort(Comparator.comparing(Agenda::getOrderNum));
        return new AgendaInfoResponse(meeting, agendaList);
    }

    public AgendaInfoResponse changeAgendaOrder(Long meetingId, List<Long> agendaIds) {
        Meeting meeting = meetingRepository.findById(meetingId)
            .orElseThrow(() -> new NotFoundError(ErrorCode.RESOURCE_NOT_FOUND,
                Collections.singletonMap("MeetingId", "Meeting not found")));
        List<Agenda> agendaList = agendaRepository.findByMeetingId(meetingId);

        if (agendaList.size() != agendaIds.stream().distinct().count()) {
            throw new BadRequestError(BadRequestError.ErrorCode.VALIDATION_FAILED,
                Collections.singletonMap("AgendaIds", "Agenda Ids are not unique"));
        }

        for (int i = 0; i < agendaIds.size(); i++) {
            Long agendaId = agendaIds.get(i);
            Agenda agenda = agendaList.stream()
                .filter(a -> a.getId().equals(agendaId))
                .findFirst()
                .orElseThrow(() -> new NotFoundError(ErrorCode.RESOURCE_NOT_FOUND,
                    Collections.singletonMap("AgendaId", "Agenda Id " + agendaId + " not found")));
            agenda.setOrderNum(i + 1);
        }
        agendaList.sort(Comparator.comparing(Agenda::getOrderNum));

        return new AgendaInfoResponse(meeting, agendaList);
    }
}
