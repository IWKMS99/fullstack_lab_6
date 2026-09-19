import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, expect, it, vi} from 'vitest';
import RoomFormModal from '../RoomFormModal';
import '../../../../i18n';

afterEach(cleanup);
const base = () => ({isOpen: true, mode: 'create' as const, onClose: vi.fn(), onSubmit: vi.fn()});

it('does not render when closed and validates missing room name', async () => {
  const props = base();
  const view = render(<RoomFormModal {...props} isOpen={false} />);
  expect(screen.queryByRole('button', {name: 'Создать'})).not.toBeInTheDocument();
  view.rerender(<RoomFormModal {...props} />);
  fireEvent.click(screen.getByRole('button', {name: 'Создать'}));
  expect(await screen.findByText('Название обязательно')).toBeInTheDocument();
  expect(props.onSubmit).not.toHaveBeenCalled();
});

it('submits trimmed valid values and shows server validation failure', async () => {
  const props = base();
  render(<RoomFormModal {...props} errorMessage="Room already exists" />);
  fireEvent.change(screen.getByRole('textbox', {name: 'Название'}), {target: {value: '  Atlas  '}});
  fireEvent.click(screen.getByRole('button', {name: 'Создать'}));
  await waitFor(() => expect(props.onSubmit).toHaveBeenCalledWith({name: 'Atlas', floor: 1, capacity: 1}));
  expect(screen.getByText('Room already exists')).toBeInTheDocument();
});

it('disables submission during saving and resets edit values on reopen', () => {
  const props = base();
  const view = render(<RoomFormModal {...props} mode="edit" isPending initialValues={{name: 'Orion', floor: 2, capacity: 8}} />);
  expect(screen.getByRole('button', {name: 'Сохранение...'})).toBeDisabled();
  expect(screen.getByRole('button', {name: 'Отмена'})).toBeDisabled();
  view.rerender(<RoomFormModal {...props} mode="edit" initialValues={{name: 'Vega', floor: 3, capacity: 9}} />);
  expect(screen.getByRole('textbox', {name: 'Название'})).toHaveValue('Vega');
  fireEvent.click(screen.getByRole('button', {name: 'Отмена'}));
  expect(props.onClose).toHaveBeenCalledOnce();
});

it('preserves edited input when a parent renders equivalent initial values', async () => {
  const props = base();
  const view = render(<RoomFormModal {...props} mode="edit" initialValues={{name: 'Orion', floor: 2, capacity: 8}} />);
  fireEvent.change(screen.getByRole('textbox', {name: 'Название'}), {target: {value: 'Orion updated'}});
  view.rerender(<RoomFormModal {...props} mode="edit" initialValues={{name: 'Orion', floor: 2, capacity: 8}} />);
  expect(screen.getByRole('textbox', {name: 'Название'})).toHaveValue('Orion updated');
  fireEvent.click(screen.getByRole('button', {name: 'Сохранить'}));
  await waitFor(() => expect(props.onSubmit).toHaveBeenCalledWith({name: 'Orion updated', floor: 2, capacity: 8}));
});

it('starts a fresh create form after closing without resetting input while open', async () => {
  const props = base();
  const view = render(<RoomFormModal {...props} isOpen={false} />);
  view.rerender(<RoomFormModal {...props} />);
  fireEvent.change(screen.getByRole('textbox', {name: 'Название'}), {target: {value: 'First draft'}});
  view.rerender(<RoomFormModal {...props} errorMessage="Retry" />);
  expect(screen.getByRole('textbox', {name: 'Название'})).toHaveValue('First draft');
  view.rerender(<RoomFormModal {...props} isOpen={false} />);
  view.rerender(<RoomFormModal {...props} />);
  expect(screen.getByRole('textbox', {name: 'Название'})).toHaveValue('');
  fireEvent.change(screen.getByRole('textbox', {name: 'Название'}), {target: {value: 'Second draft'}});
  fireEvent.click(screen.getByRole('button', {name: 'Создать'}));
  await waitFor(() => expect(props.onSubmit).toHaveBeenCalledWith({name: 'Second draft', floor: 1, capacity: 1}));
});
