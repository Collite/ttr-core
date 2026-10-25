// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { SourceFilesDrawer } from '../SourceFilesDrawer.js';
it('applies a new whole-file query through one source cache write, without persisting through HTTP', async () => {
  const write = vi.fn(async () => []),
    close = vi.fn();
  render(<SourceFilesDrawer files={() => []} write={write} onClose={close} />);
  fireEvent.change(screen.getByLabelText('Source path'), {
    target: { value: 'model/er/returns-review.ttrm' },
  });
  fireEvent.change(screen.getByLabelText('Source text'), {
    target: { value: 'package hartland\nmodel er\ndef query returns_review {}' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Apply to model' }));
  await waitFor(() => expect(close).toHaveBeenCalledOnce());
  expect(write).toHaveBeenCalledExactlyOnceWith({
    path: 'model/er/returns-review.ttrm',
    text: 'package hartland\nmodel er\ndef query returns_review {}',
  });
});
it('retains the text on refusal and does not overwrite an existing file from New source file', async () => {
  const write = vi.fn(async () => {
      throw new Error('Use a regular model/*.ttrm source path.');
    }),
    close = vi.fn();
  render(
    <SourceFilesDrawer
      files={() => [{ path: 'model/er/existing.ttrm', text: 'model er' }]}
      write={write}
      onClose={close}
    />
  );
  fireEvent.change(screen.getByLabelText('Source path'), {
    target: { value: 'model/er/existing.ttrm' },
  });
  fireEvent.change(screen.getByLabelText('Source text'), {
    target: { value: 'model er\nnew text' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Apply to model' }));
  expect(await screen.findByText(/already exists/)).toBeInTheDocument();
  expect(write).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText('Source path'), { target: { value: '../bad.ttrm' } });
  fireEvent.click(screen.getByRole('button', { name: 'Apply to model' }));
  await screen.findByText(/regular model/);
  expect(screen.getByLabelText('Source text')).toHaveValue('model er\nnew text');
  expect(close).not.toHaveBeenCalled();
});

it('R148-R11 refuses Apply when a structured edit changed the selected file', async () => {
  const file = { path: 'model/er/a.ttrm', text: 'model er\nold' };
  const write = vi.fn(async () => []);
  render(<SourceFilesDrawer files={() => [file]} write={write} onClose={vi.fn()} />);
  fireEvent.change(screen.getByLabelText('File'), { target: { value: file.path } });
  fireEvent.change(screen.getByLabelText('Source text'), { target: { value: 'drawer edit' } });
  file.text = 'form edit';
  fireEvent.click(screen.getByRole('button', { name: 'Apply to model' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('changed since you opened');
  expect(write).not.toHaveBeenCalled();
  expect(screen.getByLabelText('Source text')).toHaveValue('drawer edit');
});
