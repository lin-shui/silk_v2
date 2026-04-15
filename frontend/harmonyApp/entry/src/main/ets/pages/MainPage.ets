import { SilkColors } from '../common/Theme';
import { router as appRouter } from '../common/Router';
import { GroupListPage } from './GroupListPage';
import { WorkflowPage } from './WorkflowPage';
import { KnowledgeBasePage } from './KnowledgeBasePage';

@Component
export struct MainPage {
  @State currentTab: string = 'silk';

  build() {
    Row() {
      // Nav Rail
      Column() {
        // Logo
        Text('SILK')
          .fontSize(14)
          .fontWeight(FontWeight.Bold)
          .fontColor(Color.White)
          .letterSpacing(2)
          .margin({ bottom: 20 })

        // Silk tab
        this.buildNavItem('silk', '💬', 'Silk')
        // Workflow tab
        this.buildNavItem('workflow', '🔗', '工作流')
        // Knowledge tab
        this.buildNavItem('knowledge', '📚', '知识库')

        Blank()

        // Settings
        Text('⚙️')
          .fontSize(20)
          .padding(8)
          .borderRadius(8)
          .onClick(() => appRouter.goToSettings())
      }
      .width(72)
      .height('100%')
      .padding({ top: 16, bottom: 16 })
      .alignItems(HorizontalAlign.Center)
      .linearGradient({
        angle: 180,
        colors: [
          [SilkColors.primaryDark, 0],
          [SilkColors.primary, 1]
        ]
      })

      // Content area
      Column() {
        if (this.currentTab === 'silk') {
          GroupListPage()
        } else if (this.currentTab === 'workflow') {
          WorkflowPage()
        } else {
          KnowledgeBasePage()
        }
      }
      .layoutWeight(1)
      .height('100%')
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  buildNavItem(tabId: string, icon: string, label: string) {
    Column() {
      Text(icon)
        .fontSize(22)
      Text(label)
        .fontSize(10)
        .fontColor(Color.White)
        .margin({ top: 2 })
    }
    .width(56)
    .padding(8)
    .margin({ bottom: 4 })
    .borderRadius(12)
    .alignItems(HorizontalAlign.Center)
    .backgroundColor(this.currentTab === tabId ? '#40FFFFFF' : '#00FFFFFF')
    .onClick(() => {
      this.currentTab = tabId;
    })
  }
}
