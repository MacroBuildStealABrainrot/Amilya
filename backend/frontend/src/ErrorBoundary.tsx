import {Component} from 'react';
import type {ErrorInfo,ReactNode} from 'react';
export class ErrorBoundary extends Component<{children:ReactNode},{failed:boolean}> {
 state={failed:false};
 static getDerivedStateFromError(){return {failed:true}}
 componentDidCatch(_error:Error,_info:ErrorInfo){/* Clinical content is deliberately excluded from console telemetry. */}
 render(){if(this.state.failed)return <main className="page-content narrow" style={{padding:'48px 24px'}}><div className="eyebrow">WOUNDCoPILOT</div><h1>The workspace could not be displayed.</h1><p>Your saved server records and encrypted device recovery copies have not been cleared. Reload the workspace to restore your session.</p><button className="button primary" onClick={()=>window.location.reload()}>Reload workspace</button></main>;return this.props.children}
}
