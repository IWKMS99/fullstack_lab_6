import {describe, expect, it} from 'vitest';
import {BOOKING_BOUNDARY_POINTS} from '../bookingConstants';
import {buildSlotsFromRange, getDurationHours, getValidFromBoundaries, getValidToBoundaries} from '../bookingRange';
import {validateSlotRange} from '../validateSlotRange';
import {reconcileSelectionAfterConflict} from '../reconcileSelectionAfterConflict';
import {buildRange, getAvailableSlotsForRoom, getDaySlots, isContiguousSlots, minutesToTime, normalizeTime, sortSlots, timeToMinutes} from '../timeSlots';
import type {ScheduleView} from '../../../../types/booking';

const daySlots = BOOKING_BOUNDARY_POINTS.slice(0, -1);
const options = {daySlots, availableSlots: new Set(daySlots)};
const schedule: ScheduleView = {timeSlots: ['09:00', '09:30', '10:00', '10:30'].map(time => ({time, rooms: [{roomId: 'r1', roomName: 'Room', capacity: 8, floor: 1, isAvailable: time !== '10:00'}]}))};

describe('booking boundaries and business rules', () => {
  it('enumerates every half-hour including the middle of a two-hour booking', () => {
    expect(buildSlotsFromRange('09:00', '11:00')).toEqual(['09:00', '09:30', '10:00', '10:30']);
    expect(buildSlotsFromRange('11:00', '09:00')).toEqual([]);
    expect(getDurationHours('09:00', '10:30')).toBe(1.5);
    expect(getDurationHours('11:00', '09:00')).toBe(0);
  });
  it('accepts four hours but rejects empty, duplicate, gapped, missing or occupied slots', () => {
    expect(validateSlotRange(buildSlotsFromRange('09:00', '13:00'), options).ok).toBe(true);
    for (const range of [[], ['09:00', '09:00'], ['09:00', '10:00'], ['08:00'], buildSlotsFromRange('09:00', '13:30')]) {
      expect(validateSlotRange(range, options).ok).toBe(false);
    }
    expect(validateSlotRange(['09:00', '09:30'], {...options, availableSlots: new Set(['09:00'])}).ok).toBe(false);
    expect(validateSlotRange(['08:30'], {daySlots: ['08:30'], availableSlots: new Set(['08:30'])}).ok).toBe(false);
    expect(validateSlotRange(['18:00'], {daySlots: ['18:00'], availableSlots: new Set(['18:00'])}).ok).toBe(false);
  });
  it('never skips an occupied half-hour inside a proposed range', () => {
    const availableSlots = new Set(daySlots.filter(slot => slot !== '09:30'));
    const common = {boundaries: BOOKING_BOUNDARY_POINTS, selectedDate: new Date(2030, 0, 2), now: new Date(2030, 0, 1, 8), daySlots, availableSlots};
    expect(getValidToBoundaries({...common, from: '09:00'})).toEqual(['09:30']);
    expect(getValidFromBoundaries(common)).not.toContain('09:30');
    expect(getValidFromBoundaries(common)).not.toContain('18:00');
  });
  it('excludes current and elapsed starts only on the selected day', () => {
    const now = new Date(2030, 0, 2, 10, 0);
    const common = {...options, boundaries: BOOKING_BOUNDARY_POINTS, selectedDate: now, now};
    expect(getValidFromBoundaries(common)[0]).toBe('10:30');
    expect(getValidToBoundaries({...common, from: '10:00'})).toEqual([]);
    expect(getValidFromBoundaries({...common, selectedDate: new Date(2030, 0, 3)})[0]).toBe('09:00');
  });
  it('reconciles a conflict to the first contiguous available range', () => {
    expect(reconcileSelectionAfterConflict(['09:00', '09:30', '10:00', '10:30'], schedule, 'r1')).toEqual({adjustedSlots: ['09:00', '09:30'], removedSlots: ['10:00', '10:30']});
    expect(reconcileSelectionAfterConflict(['10:00'], schedule, 'r1').adjustedSlots).toEqual([]);
    expect(reconcileSelectionAfterConflict(['09:00'], null, 'r1').removedSlots).toEqual(['09:00']);
    expect(reconcileSelectionAfterConflict([], schedule, null).adjustedSlots).toEqual([]);
  });
  it('normalizes schedule data without mutating the original selection', () => {
    expect(normalizeTime('09:30:00')).toBe('09:30');
    expect(timeToMinutes('09:30')).toBe(570);
    expect(minutesToTime(570)).toBe('09:30');
    expect(getDaySlots(null)).toEqual([]);
    expect(getDaySlots(schedule)).toEqual(['09:00', '09:30', '10:00', '10:30']);
    expect([...getAvailableSlotsForRoom(schedule, 'r1')]).toEqual(['09:00', '09:30', '10:30']);
    expect(getAvailableSlotsForRoom(null, null).size).toBe(0);
    expect(getAvailableSlotsForRoom(schedule, 'missing').size).toBe(0);
    const input = ['10:00', '09:30'];
    expect(sortSlots(input)).toEqual(['09:30', '10:00']);
    expect(input).toEqual(['10:00', '09:30']);
    expect(isContiguousSlots(input)).toBe(true);
    expect(buildRange(daySlots, '10:00', '09:00')).toEqual(['09:00', '09:30']);
    expect(buildRange(daySlots, '10:00', '10:00')).toEqual(['10:00']);
    expect(buildRange(daySlots, 'missing', '10:00')).toEqual([]);
  });
});
