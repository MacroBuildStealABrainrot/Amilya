import {afterEach,expect,it} from 'vitest';
import {cleanup,render,screen} from '@testing-library/react';
import {KeptPhoto} from './KeptPhoto';
afterEach(cleanup);
const later=new Date(Date.now()+3600_000).toISOString(),earlier=new Date(Date.now()-60_000).toISOString();
it('shows a kept photo from the authenticated photo route until it expires',()=>{
  render(<KeptPhoto evidence={{id:'photo 1',kind:'photo',metadata:{raw_media_retained:true,photo_expires_at:later}}}/>);
  expect(screen.getByRole('img',{name:'Uploaded wound photo'}).getAttribute('src')).toBe('/api/v1/evidence/photo%201/photo');
  expect(screen.getByText(/Viewable until/)).toBeTruthy();
});
it('says an expired photo was deleted and does not request it',()=>{
  render(<KeptPhoto evidence={{id:'p',kind:'photo',metadata:{raw_media_retained:true,photo_expires_at:earlier}}}/>);
  expect(screen.queryByRole('img')).toBeNull();
  expect(screen.getByText(/deleted after its viewing period/)).toBeTruthy();
});
it('renders nothing for photos that were never kept',()=>{
  const {container}=render(<KeptPhoto evidence={{id:'p',kind:'photo',metadata:{raw_media_retained:false}}}/>);
  expect(container.innerHTML).toBe('');
});
