/**
 * 简单的路由管理器
 * 用于页面导航
 */
import { router as pageRouter } from '@kit.ArkUI';

export enum PageRoute {
  LOGIN = 'pages/LoginPage',
  GROUP_LIST = 'pages/GroupListPage',
  CHAT = 'pages/ChatPage',
  TODO = 'pages/TodoPage',
  SETTINGS = 'pages/SettingsPage'
}

export enum PageName {
  Login = 'Login',
  Main = 'Main',
  GroupList = 'GroupList',
  Chat = 'Chat',
  Todo = 'Todo',
  Settings = 'Settings',
  Workflow = 'Workflow',
  KnowledgeBase = 'KnowledgeBase'
}

export class Router {
  private static instance: Router;
  private currentPage: PageName = PageName.Login;
  private pageListeners: Array<(page: PageName) => void> = [];
  
  private constructor() {}
  
  static getInstance(): Router {
    if (!Router.instance) {
      Router.instance = new Router();
    }
    return Router.instance;
  }
  
  onPageChange(listener: (page: PageName) => void): void {
    this.pageListeners.push(listener);
  }

  getCurrentPage(): PageName {
    return this.currentPage;
  }

  private notifyPageChange(page: PageName): void {
    this.currentPage = page;
    this.pageListeners.forEach((listener: (next: PageName) => void) => listener(page));
  }
  
  /**
   * 导航到登录页面
   */
  goToLogin(): void {
    this.notifyPageChange(PageName.Login);
  }
  
  /**
   * 导航到主容器（带侧边栏）
   */
  goToMain(): void {
    this.notifyPageChange(PageName.Main);
  }

  /**
   * 导航到群组列表
   */
  goToGroupList(): void {
    this.notifyPageChange(PageName.Main);
  }
  
  /**
   * 导航到聊天页面（系统页面栈 pushUrl，侧滑/返回键关闭聊天而非退出应用）
   * 入参已由调用方写入 AppStorage（currentGroupId 等）
   */
  goToChat(_groupId: string, _groupName: string): void {
    void _groupId;
    void _groupName;
    pageRouter.pushUrl({ url: 'pages/ChatPage' }).catch((err: Error) => {
      console.error('[Router] pushUrl ChatPage failed:', err.message);
    });
  }

  /**
   * 导航到待办页面
   */
  goToTodo(): void {
    this.notifyPageChange(PageName.Todo);
  }
  
  /**
   * 导航到设置页面
   */
  goToSettings(): void {
    this.notifyPageChange(PageName.Settings);
  }
  
  /**
   * 返回上一页
   */
  goBack(): void {
    this.notifyPageChange(PageName.GroupList);
  }
}

// 导出单例
export const router = Router.getInstance();
